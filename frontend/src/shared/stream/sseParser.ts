import type { ParsedSseEvent } from '@/shared/contracts/types';
import { API_BASE_PATH, getAccessToken, refreshAccessTokenSingleFlight } from '@/shared/api/apiClient';

export interface SseCallbacks {
  onEvent: (event: ParsedSseEvent) => void;
  onHeartbeat?: () => void;
}

/**
 * Parçalanmış (chunked) UTF-8 metin akışını RFC 8895 Server-Sent Events kurallarına göre ayrıştırır.
 * Çok satırlı data: alanlarını, id:, event: ve :heartbeat yorumlarını destekler.
 */
export function createSseChunkParser(callbacks: SseCallbacks) {
  let buffer = '';

  const processBlock = (rawBlock: string) => {
    if (!rawBlock) return;
    const lines = rawBlock.split('\n');
    let eventName = 'message';
    let eventId: string | null = null;
    const dataLines: string[] = [];
    let sawComment = false;

    for (const line of lines) {
      if (!line) continue;
      if (line.startsWith(':')) {
        sawComment = true;
        continue;
      }
      const colonIdx = line.indexOf(':');
      const field = colonIdx === -1 ? line : line.slice(0, colonIdx);
      let value = colonIdx === -1 ? '' : line.slice(colonIdx + 1);
      if (value.startsWith(' ')) {
        value = value.slice(1);
      }

      if (field === 'event') {
        eventName = value || 'message';
      } else if (field === 'id') {
        if (!value.includes('\0')) {
          eventId = value;
        }
      } else if (field === 'data') {
        dataLines.push(value);
      }
    }

    if (dataLines.length > 0 || eventName !== 'message') {
      callbacks.onEvent({
        id: eventId,
        event: eventName,
        data: dataLines.join('\n'),
      });
    } else if (sawComment) {
      callbacks.onHeartbeat?.();
    }
  };

  return {
    push(chunk: string): void {
      buffer += chunk.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
      let boundaryIdx = buffer.indexOf('\n\n');
      while (boundaryIdx !== -1) {
        const block = buffer.slice(0, boundaryIdx);
        buffer = buffer.slice(boundaryIdx + 2);
        processBlock(block);
        boundaryIdx = buffer.indexOf('\n\n');
      }
    },
    flush(): void {
      if (buffer.trim()) {
        processBlock(buffer);
      }
      buffer = '';
    },
  };
}

/**
 * SseRing.java formatı: "<epochUuid>:<sequence>" (veya düz sayısal sequence).
 * Mükerrer (duplicate) ve sıra dışı (out-of-order) olayların çift etki üretmesini engeller;
 * boşluk (gap) veya epoch değişiminde REST resync tetikler.
 */
export class SseSequenceGuard {
  private epoch: string | null = null;
  private lastSequence: number | null = null;
  private lastRawId: string | null = null;

  public getLastEventId(): string | null {
    return this.lastRawId;
  }

  public evaluate(event: ParsedSseEvent): { accept: boolean; resyncRequired: boolean } {
    if (event.event === 'resync.required') {
      if (event.id) {
        this.recordRaw(event.id);
      }
      return { accept: false, resyncRequired: true };
    }

    if (!event.id) {
      return { accept: true, resyncRequired: false };
    }

    const parsed = parseCursorId(event.id);
    if (!parsed) {
      if (event.id === this.lastRawId) {
        return { accept: false, resyncRequired: false };
      }
      this.lastRawId = event.id;
      return { accept: true, resyncRequired: false };
    }

    // İlk olay
    if (this.epoch === null || this.lastSequence === null) {
      this.epoch = parsed.epoch;
      this.lastSequence = parsed.sequence;
      this.lastRawId = event.id;
      return { accept: true, resyncRequired: false };
    }

    // Sunucu yeniden başlamış (yeni SseRing epoch) -> kabul et ve REST snapshot yenile
    if (parsed.epoch !== this.epoch) {
      this.epoch = parsed.epoch;
      this.lastSequence = parsed.sequence;
      this.lastRawId = event.id;
      return { accept: true, resyncRequired: true };
    }

    // Mükerrer veya sıra dışı eski olay -> yoksay!
    if (parsed.sequence <= this.lastSequence) {
      return { accept: false, resyncRequired: false };
    }

    // Sıra atlaması (gap) -> kabul et ama REST resync iste
    const hasGap = parsed.sequence > this.lastSequence + 1;
    this.lastSequence = parsed.sequence;
    this.lastRawId = event.id;
    return { accept: true, resyncRequired: hasGap };
  }

  public reset(): void {
    this.epoch = null;
    this.lastSequence = null;
    this.lastRawId = null;
  }

  private recordRaw(rawId: string): void {
    const parsed = parseCursorId(rawId);
    if (parsed) {
      this.epoch = parsed.epoch;
      this.lastSequence = parsed.sequence;
    }
    this.lastRawId = rawId;
  }
}

function parseCursorId(raw: string): { epoch: string; sequence: number } | null {
  const colonIdx = raw.lastIndexOf(':');
  if (colonIdx === -1) {
    const numeric = Number(raw);
    if (Number.isSafeInteger(numeric) && numeric >= 0) {
      return { epoch: 'default', sequence: numeric };
    }
    return null;
  }
  const epoch = raw.slice(0, colonIdx);
  const seqStr = raw.slice(colonIdx + 1);
  if (!epoch || !/^\d+$/.test(seqStr)) return null;
  const sequence = Number(seqStr);
  if (!Number.isSafeInteger(sequence)) return null;
  return { epoch, sequence };
}

export interface ConnectSseOptions {
  path: string;
  lastEventId?: string | null;
  signal: AbortSignal;
  onOpen?: () => void;
  onEvent: (event: ParsedSseEvent) => void;
  onHeartbeat?: () => void;
}

/**
 * GÜVENLİK KURALI:
 * Standart EventSource API başlık desteklemediği için fetch + ReadableStream kullanılır.
 * Bearer token ASLA URL query parametresine konulmaz; yalnız Authorization başlığı ile gönderilir.
 */
export async function connectAuthorizedSse(options: ConnectSseOptions): Promise<void> {
  const normalizedUrl = options.path.startsWith('/api/')
    ? options.path
    : `${API_BASE_PATH}${options.path.startsWith('/') ? options.path : `/${options.path}`}`;

  if (normalizedUrl.includes('access_token=') || normalizedUrl.includes('token=')) {
    throw new Error('SSE_SECURITY_VIOLATION_TOKEN_IN_URL');
  }

  const doOpen = async (token: string | null): Promise<Response> => {
    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
    };
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    if (options.lastEventId) {
      headers['Last-Event-ID'] = options.lastEventId;
    }
    return fetch(normalizedUrl, {
      method: 'GET',
      credentials: 'include',
      headers,
      signal: options.signal,
    });
  };

  let response = await doOpen(getAccessToken());
  if (response.status === 401 && !options.signal.aborted) {
    const refreshed = await refreshAccessTokenSingleFlight();
    if (refreshed) {
      response = await doOpen(refreshed);
    }
  }

  if (!response.ok) {
    throw new Error(`SSE_HTTP_${response.status}`);
  }

  if (!response.body) {
    throw new Error('SSE_EMPTY_BODY');
  }

  options.onOpen?.();

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  const parser = createSseChunkParser({
    onEvent: options.onEvent,
    onHeartbeat: options.onHeartbeat,
  });

  try {
    while (!options.signal.aborted) {
      const { value, done } = await reader.read();
      if (done) break;
      if (value) {
        parser.push(decoder.decode(value, { stream: true }));
      }
    }
    parser.push(decoder.decode());
    parser.flush();
  } finally {
    try {
      await reader.cancel();
    } catch {
      // Stream zaten kapandıysa sessizce çık
    }
  }
}

