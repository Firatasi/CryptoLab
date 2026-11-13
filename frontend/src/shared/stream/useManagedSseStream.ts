import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ParsedSseEvent, StreamConnectionState } from '@/shared/contracts/types';
import { connectAuthorizedSse, SseSequenceGuard } from './sseParser';

const activeStreamControllers = new Set<AbortController>();

/** Logout sırasında açık tüm SSE bağlantılarını anında sonlandırır. */
export function abortAllActiveStreams(): void {
  for (const controller of activeStreamControllers) {
    controller.abort();
  }
  activeStreamControllers.clear();
}

export interface ManagedSseOptions {
  enabled: boolean;
  path: string | null;
  resyncQueryKeys: ReadonlyArray<readonly unknown[]>;
  onAcceptedEvent?: (event: ParsedSseEvent) => void;
}

export function useManagedSseStream(options: ManagedSseOptions): {
  status: StreamConnectionState;
  lastHeartbeatAt: number | null;
} {
  const { enabled, path } = options;
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<StreamConnectionState>(enabled && path ? 'CONNECTING' : 'IDLE');
  const [lastHeartbeatAt, setLastHeartbeatAt] = useState<number | null>(null);

  const onAcceptedRef = useRef(options.onAcceptedEvent);
  const resyncKeysRef = useRef(options.resyncQueryKeys);

  useEffect(() => {
    onAcceptedRef.current = options.onAcceptedEvent;
    resyncKeysRef.current = options.resyncQueryKeys;
  });

  useEffect(() => {
    if (!enabled || !path) {
      setStatus('IDLE');
      return;
    }

    let unmounted = false;
    let attempt = 0;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let activeController: AbortController | null = null;
    const sequenceGuard = new SseSequenceGuard();

    const triggerResync = () => {
      for (const key of resyncKeysRef.current) {
        void queryClient.invalidateQueries({ queryKey: key });
      }
    };

    const runLoop = async () => {
      while (!unmounted) {
        const controller = new AbortController();
        activeController = controller;
        activeStreamControllers.add(controller);

        setStatus(attempt === 0 ? 'CONNECTING' : 'RECONNECTING');

        try {
          await connectAuthorizedSse({
            path,
            lastEventId: sequenceGuard.getLastEventId(),
            signal: controller.signal,
            onOpen: () => {
              if (unmounted) return;
              attempt = 0;
              setStatus('LIVE');
              setLastHeartbeatAt(Date.now());
            },
            onHeartbeat: () => {
              if (!unmounted) {
                setLastHeartbeatAt(Date.now());
              }
            },
            onEvent: (evt) => {
              if (unmounted) return;
              setLastHeartbeatAt(Date.now());
              const decision = sequenceGuard.evaluate(evt);
              if (decision.accept) {
                onAcceptedRef.current?.(evt);
              }
              if (decision.resyncRequired) {
                triggerResync();
              }
            },
          });

          if (unmounted || controller.signal.aborted) {
            break;
          }
          attempt += 1;
        } catch {
          if (unmounted || controller.signal.aborted) {
            break;
          }
          attempt += 1;
          // 2 ardışık başarısızlıktan sonra kullanıcıya dürüstçe Sınırlı Yoklama (DEGRADED_POLLING) göster
          setStatus(attempt >= 2 ? 'DEGRADED_POLLING' : 'RECONNECTING');
        } finally {
          activeStreamControllers.delete(controller);
        }

        const baseDelay = Math.min(1000 * Math.pow(2, Math.min(attempt, 5)), 30_000);
        const jitter = Math.floor(Math.random() * 400);
        await new Promise<void>((resolve) => {
          retryTimer = setTimeout(resolve, baseDelay + jitter);
        });
      }
    };

    void runLoop();

    return () => {
      unmounted = true;
      if (retryTimer) {
        clearTimeout(retryTimer);
      }
      if (activeController) {
        activeController.abort();
        activeStreamControllers.delete(activeController);
      }
      sequenceGuard.reset();
    };
  }, [enabled, path, queryClient]);

  return { status, lastHeartbeatAt };
}

