import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import '../index.css';

async function prepareDevFixtureWorker(): Promise<void> {
  // Production builds NEVER load MSW.
  if (!import.meta.env.DEV) {
    return;
  }
  const apiMode = import.meta.env.VITE_API_MODE ?? 'live';
  if (apiMode !== 'fixture' && apiMode !== 'hybrid') {
    if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
      try {
        const registrations = await navigator.serviceWorker.getRegistrations();
        await Promise.all(
          registrations
            .filter((r) => r.active?.scriptURL?.includes('mockServiceWorker'))
            .map((r) => r.unregister()),
        );
      } catch {
        // Ignore service worker cleanup errors
      }
    }
    return;
  }
  try {
    const { startBrowserFixtureWorker } = await import('../tests/mocks/browser');
    await Promise.race([
      startBrowserFixtureWorker(apiMode),
      new Promise<void>((resolve) => setTimeout(resolve, 1500)),
    ]);
  } catch {
    // Render application even if fixture worker fails to initialize
  }
}

void prepareDevFixtureWorker().finally(() => {
  const rootEl = document.getElementById('root');
  if (!rootEl) {
    throw new Error('Root element #root bulunamadı.');
  }
  ReactDOM.createRoot(rootEl).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
});

