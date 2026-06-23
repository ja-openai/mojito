import React from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App';
import { initializeFrontendAuth } from './auth/frontend-auth';
import {
  isContentCmsPreviewMode,
  isContentCmsSettingsDirectoryPreviewMode,
} from './page/settings/content-cms-preview-mode';

const VISIBLE_TEXT_PROTOTYPE_PATH = '/tools/mf2-editor-prototype';

function renderRoot(content: React.ReactNode) {
  createRoot(document.getElementById('root') as HTMLElement).render(
    <React.StrictMode>{content}</React.StrictMode>,
  );
}

async function renderVisibleTextPrototype() {
  const { TextAssistPrototypePage } = await import('./page/tools/TextAssistPrototypePage');
  renderRoot(<TextAssistPrototypePage />);
}

function renderApp() {
  renderRoot(<App />);
}

async function renderContentCmsPreview() {
  const { ContentCmsAuthoringPreview } = await import('./page/settings/ContentCmsAuthoringPreview');
  renderRoot(<ContentCmsAuthoringPreview />);
}

async function renderContentCmsSettingsDirectoryPreview() {
  const { ContentCmsSettingsDirectoryPreview } =
    await import('./page/settings/ContentCmsSettingsDirectoryPreview');
  renderRoot(<ContentCmsSettingsDirectoryPreview />);
}

function renderAuthenticatedApp() {
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

if (window.location.pathname === VISIBLE_TEXT_PROTOTYPE_PATH) {
  void renderVisibleTextPrototype();
} else if (import.meta.env.DEV) {
  const contentCmsPreviewMode = new URLSearchParams(window.location.search).get('cmsPreview');

  if (isContentCmsSettingsDirectoryPreviewMode(contentCmsPreviewMode)) {
    void renderContentCmsSettingsDirectoryPreview();
  } else if (isContentCmsPreviewMode(contentCmsPreviewMode)) {
    void renderContentCmsPreview();
  } else {
    renderAuthenticatedApp();
  }
} else {
  renderAuthenticatedApp();
}
