import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

export async function startBrowserFixtureWorker(mode: 'fixture' | 'hybrid') {
  const worker = setupWorker(...handlers);
  await worker.start({
    onUnhandledRequest: mode === 'hybrid' ? 'bypass' : 'warn',
  });
}

