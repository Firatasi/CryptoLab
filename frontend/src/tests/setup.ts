import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { cleanup } from '@testing-library/react';
import { server } from './mocks/server';

// jsdom matchMedia ve ResizeObserver polyfill (lightweight-charts / fancy-canvas için)
if (typeof window !== 'undefined' && !window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }),
  });
}

if (typeof window !== 'undefined' && !('ResizeObserver' in window)) {
  class MockResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Object.defineProperty(window, 'ResizeObserver', {
    writable: true,
    value: MockResizeObserver,
  });
}

if (typeof HTMLCanvasElement !== 'undefined') {
  HTMLCanvasElement.prototype.getContext = (() => null) as unknown as typeof HTMLCanvasElement.prototype.getContext;
}

// jsdom kendi AbortSignal sınıfını tanımladığı için Node undici Request/fetch ile uyumsuzluk oluşmasını önle
const OriginalRequest = globalThis.Request;
const OriginalFetch = globalThis.fetch;

class PatchedRequest extends OriginalRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    if (init && 'signal' in init) {
      super(input, { ...init, signal: undefined });
    } else {
      super(input, init);
    }
  }
}

globalThis.Request = PatchedRequest as typeof Request;
globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  if (init && 'signal' in init) {
    return OriginalFetch(input, { ...init, signal: undefined });
  }
  return OriginalFetch(input, init);
}) as typeof fetch;

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  sessionStorage.clear();
  localStorage.clear();
});

afterAll(() => {
  server.close();
});
