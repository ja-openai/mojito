import React from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App';
import { initializeFrontendAuth } from './auth/frontend-auth';

function renderApp() {
  createRoot(document.getElementById('root') as HTMLElement).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
}

initializeFrontendAuth()
  .then(({ shouldRender }) => {
    if (shouldRender) {
      renderApp();
    }
  })
  .catch(() => {
    renderApp();
  });
