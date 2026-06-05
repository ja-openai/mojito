import React from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App';
import { initializeFrontendAuth } from './auth/frontend-auth';

const VISIBLE_TEXT_PROTOTYPE_PATH = '/tools/mf2-editor-prototype';

function renderApp() {
  createRoot(document.getElementById('root') as HTMLElement).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
}

async function renderVisibleTextPrototype() {
  const { TextAssistPrototypePage } = await import('./page/tools/TextAssistPrototypePage');
  createRoot(document.getElementById('root') as HTMLElement).render(
    <React.StrictMode>
      <TextAssistPrototypePage />
    </React.StrictMode>,
  );
}

if (window.location.pathname === VISIBLE_TEXT_PROTOTYPE_PATH) {
  void renderVisibleTextPrototype();
} else {
  initializeFrontendAuth()
    .then(({ shouldRender }) => {
      if (shouldRender) {
        renderApp();
      }
    })
    .catch(() => {
      renderApp();
    });
}
