export {};

type ActionButtonConfig = {
  text: string;
  url: string;
};

type IctConfig = {
  mojitoBaseUrl: string;
  enabled: boolean;
  headerName: string;
  headerValue: string;
  actionButtons: ActionButtonConfig[];
  mtEnabled: boolean;
  mtShowErrors: boolean;
  mtEndpointUrlFormat: string;
};

type IctTextUnit = {
  repositoryName: string;
  assetName: string;
  textUnitName: string;
  locale: string;
  stack: string;
  translationType: string;
  textUnitVariant: string;
};

type ParseResult = {
  cleanedText: string;
  textUnits: IctTextUnit[];
};

type ReplacementTarget =
  | {
      type: 'attribute';
      element: Element;
      attributeName: string;
    }
  | {
      type: 'text';
      node: Text;
    };

type MachineTranslationResponse = {
  text?: string;
  data?: string;
  error?: string;
};

const DEFAULT_MT_ENDPOINT_URL_FORMAT = 'http://localhost:5173/api/machine-translation';
const DEFAULT_MOJITO_BASE_URL = 'http://localhost:5173/';
const OLD_DEFAULT_MOJITO_BASE_URL = 'http://localhost:8080/';

const DEFAULT_CONFIG: IctConfig = {
  mojitoBaseUrl: DEFAULT_MOJITO_BASE_URL,
  enabled: false,
  headerName: 'X-Mojito-Ict',
  headerValue: 'on',
  actionButtons: [],
  mtEnabled: false,
  mtShowErrors: false,
  mtEndpointUrlFormat: DEFAULT_MT_ENDPOINT_URL_FORMAT,
};

const START_DELIMITER = '\u{E0022}';
const MIDDLE_DELIMITER = '\u{E0023}';
const END_DELIMITER = '\u{E0024}';
const INNER_DELIMITER = '\u{0013}';
const TAGS_BLOCK_PATTERN = /\udb40[\udc00-\udc7e]/g;
const ATTRIBUTES_TO_SCAN = ['placeholder', 'title', 'aria-label', 'alt'];
const HOVER_CLASSES = [
  'mojito-ict-string-active',
  'mojito-ict-string-delta-active',
  'mojito-ict-string-mt',
];

let config = DEFAULT_CONFIG;
let observer: MutationObserver | null = null;
let scanScheduled = false;
let globalClickListenerInstalled = false;
const pendingScanRoots = new Set<Node>();
const enrichedElements = new WeakMap<Element, IctTextUnit[]>();
const boundElements = new WeakSet<Element>();
const mtRequestedTextNodes = new WeakSet<Text>();
const mtRequestedAttributes = new WeakMap<Element, Set<string>>();
const machineTranslationTargets = new Set<ReplacementTarget>();
const mtErrorBadges = new Map<Element, HTMLElement>();

function normalizeConfig(value: Partial<IctConfig>): IctConfig {
  const nextConfig = {
    ...DEFAULT_CONFIG,
    ...value,
    actionButtons: Array.isArray(value.actionButtons) ? value.actionButtons : [],
  };

  nextConfig.mtEndpointUrlFormat = DEFAULT_MT_ENDPOINT_URL_FORMAT;
  if (!nextConfig.mojitoBaseUrl.trim() || nextConfig.mojitoBaseUrl === OLD_DEFAULT_MOJITO_BASE_URL) {
    nextConfig.mojitoBaseUrl = DEFAULT_MOJITO_BASE_URL;
  }
  if (nextConfig.mtEnabled) {
    nextConfig.enabled = true;
  }

  return nextConfig;
}

function decodeTagsBlock(value: string): string {
  let base64 = '';
  for (const char of Array.from(value)) {
    if (
      char.charCodeAt(0) === 56128 &&
      char.charCodeAt(1) >= 56352 &&
      char.charCodeAt(1) <= 56446
    ) {
      base64 += String.fromCodePoint(32 + char.charCodeAt(1) - 56352);
      continue;
    }

    throw new Error('Unsupported character in Mojito ICT tags block.');
  }

  return new TextDecoder().decode(
    Uint8Array.from(atob(base64), (character) => character.charCodeAt(0)),
  );
}

function removeTagsBlock(value: string): string {
  return value.replace(TAGS_BLOCK_PATTERN, '');
}

function decodeMetadata(metadataBlock: string, textUnitVariant: string): IctTextUnit | null {
  try {
    const parts = decodeTagsBlock(metadataBlock).split(INNER_DELIMITER);
    return {
      repositoryName: parts[0] ?? '',
      assetName: parts[1] ?? '',
      textUnitName: parts[2] ?? '',
      locale: parts[3] ?? '',
      stack: parts[4] ?? '',
      translationType: parts.length > 5 ? (parts[5] ?? 'NATIVE') : 'NATIVE',
      textUnitVariant,
    };
  } catch (error) {
    console.debug('Unable to decode Mojito ICT metadata', error);
    return null;
  }
}

function parseIctText(value: string): ParseResult | null {
  if (!value.includes(START_DELIMITER)) {
    return null;
  }

  let cleanedText = '';
  let searchIndex = 0;
  const textUnits: IctTextUnit[] = [];

  while (searchIndex < value.length) {
    const startIndex = value.indexOf(START_DELIMITER, searchIndex);
    if (startIndex === -1) {
      cleanedText += value.slice(searchIndex);
      break;
    }

    const middleIndex = value.indexOf(MIDDLE_DELIMITER, startIndex + START_DELIMITER.length);
    const endIndex =
      middleIndex === -1
        ? -1
        : value.indexOf(END_DELIMITER, middleIndex + MIDDLE_DELIMITER.length);

    if (middleIndex === -1 || endIndex === -1) {
      cleanedText += value.slice(searchIndex);
      break;
    }

    const metadataBlock = value.slice(startIndex + START_DELIMITER.length, middleIndex);
    const textUnitVariant = removeTagsBlock(
      value.slice(middleIndex + MIDDLE_DELIMITER.length, endIndex),
    );
    const textUnit = decodeMetadata(metadataBlock, textUnitVariant);

    cleanedText += value.slice(searchIndex, startIndex) + textUnitVariant;
    searchIndex = endIndex + END_DELIMITER.length;

    if (textUnit) {
      textUnits.push(textUnit);
    }
  }

  return textUnits.length > 0 ? { cleanedText, textUnits } : null;
}

function getHoverClass(textUnits: IctTextUnit[]): string {
  const textUnit = textUnits[0];
  if (!textUnit) {
    return 'mojito-ict-string-active';
  }

  if (config.mtEnabled && textUnit.translationType === 'MT_REQUIRED') {
    return 'mojito-ict-string-mt';
  }

  return textUnit.translationType === 'DELTA_OTA'
    ? 'mojito-ict-string-delta-active'
    : 'mojito-ict-string-active';
}

function clearHoverClasses(element: Element): void {
  element.classList.remove(...HOVER_CLASSES);
}

function findEnrichedElementInPath(path: EventTarget[]): Element | null {
  for (const target of path) {
    if (target instanceof Element && enrichedElements.has(target)) {
      return target;
    }
  }

  return null;
}

function installGlobalClickListener(): void {
  if (globalClickListenerInstalled) {
    return;
  }

  document.addEventListener(
    'click',
    (event) => {
      const mouseEvent = event instanceof MouseEvent ? event : null;
      if (!config.enabled || !mouseEvent?.shiftKey) {
        return;
      }

      const element = findEnrichedElementInPath(event.composedPath());
      const textUnits = element ? enrichedElements.get(element) : null;
      if (!textUnits) {
        return;
      }

      event.preventDefault();
      event.stopImmediatePropagation();
      modal.show(textUnits);
    },
    true,
  );

  globalClickListenerInstalled = true;
}

function bindElement(element: Element): void {
  if (boundElements.has(element)) {
    return;
  }

  element.addEventListener('mouseenter', () => {
    const textUnits = enrichedElements.get(element);
    if (textUnits) {
      element.classList.add(getHoverClass(textUnits));
    }
  });

  element.addEventListener('mouseleave', () => {
    clearHoverClasses(element);
  });

  boundElements.add(element);
}

function enrichElement(element: Element, textUnits: IctTextUnit[]): void {
  enrichedElements.set(element, textUnits);
  element.classList.add('mojito-ict-string');
  bindElement(element);
}

function hasRequestedMachineTranslation(target: ReplacementTarget): boolean {
  if (target.type === 'text') {
    return mtRequestedTextNodes.has(target.node);
  }

  return mtRequestedAttributes.get(target.element)?.has(target.attributeName) ?? false;
}

function markMachineTranslationRequested(target: ReplacementTarget): void {
  if (target.type === 'text') {
    mtRequestedTextNodes.add(target.node);
    return;
  }

  const attributes = mtRequestedAttributes.get(target.element) ?? new Set<string>();
  attributes.add(target.attributeName);
  mtRequestedAttributes.set(target.element, attributes);
}

function unmarkMachineTranslationRequested(target: ReplacementTarget): void {
  if (target.type === 'text') {
    mtRequestedTextNodes.delete(target.node);
    return;
  }

  const attributes = mtRequestedAttributes.get(target.element);
  attributes?.delete(target.attributeName);
  if (attributes?.size === 0) {
    mtRequestedAttributes.delete(target.element);
  }
}

function replaceTargetText(target: ReplacementTarget, value: string): void {
  if (target.type === 'text') {
    target.node.nodeValue = value;
    return;
  }

  target.element.setAttribute(target.attributeName, value);
}

function getTargetElement(target: ReplacementTarget): Element | null {
  return target.type === 'text' ? target.node.parentElement : target.element;
}

function clearMachineTranslationError(target: ReplacementTarget): void {
  const element = getTargetElement(target);
  if (!element) {
    return;
  }

  mtErrorBadges.get(element)?.remove();
  mtErrorBadges.delete(element);
  element.classList.remove('mojito-mt-ict-error');
  element.removeAttribute('data-mojito-mt-error');
}

function clearAllMachineTranslationErrors(): void {
  for (const [element, badge] of mtErrorBadges.entries()) {
    badge.remove();
    element.classList.remove('mojito-mt-ict-error');
    element.removeAttribute('data-mojito-mt-error');
  }
  mtErrorBadges.clear();
}

function showMachineTranslationError(target: ReplacementTarget, error: unknown): void {
  if (!config.mtShowErrors) {
    return;
  }

  const element = getTargetElement(target);
  if (!element) {
    return;
  }

  const message = error instanceof Error ? error.message : String(error);
  const badge = mtErrorBadges.get(element) ?? document.createElement('span');
  badge.className = 'mojito-mt-ict-error-badge';
  badge.textContent = 'MT error';
  badge.title = message;
  badge.setAttribute('aria-label', `Machine translation failed: ${message}`);

  if (!badge.isConnected && target.type === 'text') {
    element.appendChild(badge);
  }

  element.classList.add('mojito-mt-ict-error');
  element.setAttribute('data-mojito-mt-error', message);
  mtErrorBadges.set(element, badge);
}

function trackMachineTranslationTarget(textUnits: IctTextUnit[], target: ReplacementTarget): void {
  if (textUnits[0]?.translationType === 'MT_REQUIRED') {
    machineTranslationTargets.add(target);
  }
}

function requestPendingMachineTranslations(): void {
  if (!config.mtEnabled) {
    return;
  }

  for (const target of machineTranslationTargets) {
    const element = target.type === 'text' ? target.node.parentElement : target.element;
    const textUnits = element ? enrichedElements.get(element) : null;
    if (textUnits) {
      requestMachineTranslation(textUnits, target);
    }
  }
}

function fetchMachineTranslationDirect(textUnit: IctTextUnit): Promise<string> {
  return fetch(config.mtEndpointUrlFormat, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      textSource: textUnit.textUnitVariant,
      sourceBcp47Tag: 'en',
      targetBcp47Tag: textUnit.locale,
      skipFunctionalProtection: false,
      skipLeveraging: true,
    }),
  })
    .then((response) => {
      if (!response.ok) {
        throw new Error(`MT request failed with status ${response.status} for ${response.url}`);
      }

      return response.json() as Promise<MachineTranslationResponse>;
    })
    .then((response) => response.data ?? response.text ?? '');
}

function fetchMachineTranslationFromBackground(textUnit: IctTextUnit): Promise<string> {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage(
      {
        textUnitVariant: textUnit.textUnitVariant,
        locale: textUnit.locale,
      },
      (response?: MachineTranslationResponse) => {
        const error = chrome.runtime.lastError?.message ?? response?.error;
        if (error) {
          reject(new Error(error));
          return;
        }

        resolve(response?.data ?? response?.text ?? '');
      },
    );
  });
}

function fetchMachineTranslation(textUnit: IctTextUnit): Promise<string> {
  return fetchMachineTranslationDirect(textUnit).catch((error: unknown) => {
    console.warn(
      'Mojito ICT direct MT request failed; retrying through extension background',
      error instanceof Error ? error.message : String(error),
    );
    return fetchMachineTranslationFromBackground(textUnit);
  });
}

function requestMachineTranslation(textUnits: IctTextUnit[], target: ReplacementTarget): void {
  const textUnit = textUnits[0];
  if (
    !config.mtEnabled ||
    !textUnit ||
    textUnit.translationType !== 'MT_REQUIRED' ||
    hasRequestedMachineTranslation(target)
  ) {
    return;
  }

  markMachineTranslationRequested(target);
  clearMachineTranslationError(target);
  fetchMachineTranslation(textUnit)
    .then((translation) => {
      if (!translation) {
        showMachineTranslationError(target, 'MT response was empty');
        unmarkMachineTranslationRequested(target);
        return;
      }

      replaceTargetText(target, translation);
      const element = getTargetElement(target);
      element?.classList.add('mojito-mt-ict-string-static');
    })
    .catch((error: unknown) => {
      console.warn(
        'Mojito ICT MT request failed',
        error instanceof Error ? error.message : String(error),
      );
      showMachineTranslationError(target, error);
      unmarkMachineTranslationRequested(target);
    });
}

function processTextNode(node: Text): void {
  const value = node.nodeValue;
  const parentElement = node.parentElement;
  if (!value || !parentElement || shouldSkipElement(parentElement)) {
    return;
  }

  const result = parseIctText(value);
  if (!result) {
    return;
  }

  node.nodeValue = result.cleanedText;
  enrichElement(parentElement, result.textUnits);
  const target: ReplacementTarget = { type: 'text', node };
  trackMachineTranslationTarget(result.textUnits, target);
  requestMachineTranslation(result.textUnits, target);
}

function processElementAttributes(element: Element): void {
  if (shouldSkipElement(element)) {
    return;
  }

  for (const attributeName of ATTRIBUTES_TO_SCAN) {
    const value = element.getAttribute(attributeName);
    if (!value) {
      continue;
    }

    const result = parseIctText(value);
    if (!result) {
      continue;
    }

    element.setAttribute(attributeName, result.cleanedText);
    enrichElement(element, result.textUnits);
    const target: ReplacementTarget = {
      type: 'attribute',
      element,
      attributeName,
    };
    trackMachineTranslationTarget(result.textUnits, target);
    requestMachineTranslation(result.textUnits, target);
  }
}

function shouldSkipElement(element: Element): boolean {
  const tagName = element.tagName.toLowerCase();
  return (
    tagName === 'script' ||
    tagName === 'style' ||
    tagName === 'noscript' ||
    tagName === 'textarea' ||
    element.id === 'mojito-ict-extension-root'
  );
}

function scanRoot(root: Node): void {
  if (root.nodeType === Node.TEXT_NODE) {
    processTextNode(root as Text);
    return;
  }

  if (!(root instanceof Element || root instanceof Document || root instanceof DocumentFragment)) {
    return;
  }

  if (root instanceof Element) {
    processElementAttributes(root);
  }

  const elementRoot = root instanceof Element ? root : root.firstElementChild;
  if (elementRoot) {
    for (const element of elementRoot.querySelectorAll('*')) {
      processElementAttributes(element);
    }
  }

  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let currentNode = walker.nextNode();
  while (currentNode) {
    processTextNode(currentNode as Text);
    currentNode = walker.nextNode();
  }
}

function scheduleScan(root: Node = document.body): void {
  if (!config.enabled || !document.body) {
    return;
  }

  pendingScanRoots.add(root);
  if (scanScheduled) {
    return;
  }

  scanScheduled = true;
  window.setTimeout(() => {
    scanScheduled = false;
    const roots = Array.from(pendingScanRoots);
    pendingScanRoots.clear();

    for (const scanRootCandidate of roots) {
      scanRoot(scanRootCandidate);
    }
  }, 50);
}

function installGlobalStyles(): void {
  if (document.getElementById('mojito-ict-extension-styles')) {
    return;
  }

  const style = document.createElement('style');
  style.id = 'mojito-ict-extension-styles';
  style.textContent = `
    .mojito-ict-string-active {
      outline: 1px solid #16a34a !important;
      outline-offset: 1px !important;
    }

    .mojito-ict-string-delta-active {
      outline: 1px solid #2563eb !important;
      outline-offset: 1px !important;
    }

    .mojito-ict-string-mt {
      outline: 1px solid #f59e0b !important;
      outline-offset: 1px !important;
    }

    .mojito-mt-ict-string-static::after {
      content: " MT";
      color: #f59e0b;
      font-size: 8px;
      font-weight: 700;
    }

    .mojito-mt-ict-error {
      outline: 1px solid #dc2626 !important;
      outline-offset: 1px !important;
    }

    .mojito-mt-ict-error-badge {
      display: inline-block;
      margin-left: 4px;
      border-radius: 999px;
      padding: 1px 5px;
      background: #fee2e2;
      color: #b91c1c;
      font-size: 9px;
      font-weight: 700;
      line-height: 1.4;
      vertical-align: middle;
    }
  `;
  document.documentElement.appendChild(style);
}

function installObserver(): void {
  if (observer || !document.body) {
    return;
  }

  observer = new MutationObserver((records) => {
    for (const record of records) {
      if (record.type === 'attributes' || record.type === 'characterData') {
        scheduleScan(record.target);
      }

      for (const node of record.addedNodes) {
        scheduleScan(node);
      }
    }
  });

  observer.observe(document.body, {
    attributes: true,
    attributeFilter: ATTRIBUTES_TO_SCAN,
    characterData: true,
    childList: true,
    subtree: true,
  });
}

function activate(): void {
  if (!config.enabled || !document.body) {
    return;
  }

  installGlobalStyles();
  modal.mount();
  installGlobalClickListener();
  installObserver();
  scheduleScan(document.body);
}

function openWorkbench(textUnit: IctTextUnit): void {
  const url = new URL('workbench', ensureTrailingSlash(config.mojitoBaseUrl));
  url.searchParams.append('repoNames[]', textUnit.repositoryName);
  url.searchParams.append('bcp47Tags[]', textUnit.locale);
  url.searchParams.set('searchAttribute', 'stringId');
  url.searchParams.set('searchType', 'exact');
  url.searchParams.set('searchText', textUnit.textUnitName);
  window.open(url.toString(), '_blank', 'noopener');
}

function openAction(textUnit: IctTextUnit, actionButton: ActionButtonConfig): void {
  const url = new URL(actionButton.url, ensureTrailingSlash(config.mojitoBaseUrl));
  url.searchParams.set('repoName', textUnit.repositoryName);
  url.searchParams.set('locales', textUnit.locale);
  url.searchParams.set('searchText', textUnit.textUnitName);
  url.searchParams.set('assetName', textUnit.assetName);
  window.open(url.toString(), '_blank', 'noopener');
}

function ensureTrailingSlash(value: string): string {
  return value.endsWith('/') ? value : `${value}/`;
}

class IctModal {
  private host: HTMLDivElement | null = null;
  private shadowRoot: ShadowRoot | null = null;
  private textUnits: IctTextUnit[] = [];
  private selectedIndex = 0;

  mount(): void {
    if (this.host) {
      return;
    }

    const host = document.createElement('div');
    host.id = 'mojito-ict-extension-root';
    document.documentElement.appendChild(host);
    this.host = host;
    this.shadowRoot = host.attachShadow({ mode: 'open' });
    this.render(false);
  }

  show(textUnits: IctTextUnit[]): void {
    this.textUnits = textUnits;
    this.selectedIndex = 0;
    this.mount();
    this.render(true);
  }

  private hide(): void {
    this.render(false);
  }

  private getSelectedTextUnit(): IctTextUnit | null {
    return this.textUnits[this.selectedIndex] ?? null;
  }

  private render(visible: boolean): void {
    if (!this.shadowRoot) {
      return;
    }

    const selectedTextUnit = this.getSelectedTextUnit();
    this.shadowRoot.innerHTML = `
      <style>
        :host {
          all: initial;
          color-scheme: light;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        }

        .backdrop {
          position: fixed;
          inset: 0;
          z-index: 2147483647;
          display: ${visible ? 'flex' : 'none'};
          align-items: center;
          justify-content: center;
          background: rgba(15, 23, 42, 0.35);
        }

        .modal {
          box-sizing: border-box;
          width: min(720px, calc(100vw - 32px));
          max-height: min(720px, calc(100vh - 32px));
          overflow: auto;
          border-radius: 8px;
          background: #fff;
          box-shadow: 0 20px 70px rgba(15, 23, 42, 0.28);
          color: #172033;
        }

        header,
        footer {
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 12px;
          padding: 14px 16px;
          border-bottom: 1px solid #e4e7ec;
        }

        footer {
          justify-content: flex-end;
          border-top: 1px solid #e4e7ec;
          border-bottom: 0;
        }

        h2 {
          margin: 0;
          font-size: 16px;
          font-weight: 650;
        }

        .body {
          display: grid;
          gap: 10px;
          padding: 12px;
        }

        .item {
          display: block;
          width: 100%;
          border: 1px solid #d0d5dd;
          border-radius: 7px;
          padding: 10px;
          background: #fff;
          color: inherit;
          cursor: pointer;
          font: inherit;
          text-align: left;
        }

        .item[data-selected="true"] {
          border-color: #2563eb;
          background: #eff6ff;
        }

        .meta {
          display: grid;
          grid-template-columns: 120px 1fr;
          gap: 4px 10px;
          font-size: 12px;
          line-height: 1.35;
        }

        .label {
          color: #667085;
        }

        .value,
        .variant,
        .stack {
          overflow-wrap: anywhere;
        }

        .variant,
        .stack {
          margin-top: 8px;
          border-radius: 6px;
          background: #f8fafc;
          padding: 8px;
          color: #344054;
          font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
          font-size: 12px;
          white-space: pre-wrap;
        }

        button {
          border: 1px solid #c7cedd;
          border-radius: 6px;
          background: #fff;
          color: #172033;
          cursor: pointer;
          font: inherit;
          font-size: 13px;
          padding: 7px 10px;
        }

        button.primary {
          border-color: #2563eb;
          background: #2563eb;
          color: #fff;
        }
      </style>
      <div class="backdrop">
        <section class="modal" role="dialog" aria-modal="true" aria-label="Mojito ICT text units">
          <header>
            <h2>Mojito ICT</h2>
            <button type="button" data-action="close" aria-label="Close">Close</button>
          </header>
          <div class="body">
            ${this.renderTextUnits()}
          </div>
          <footer>
            ${this.renderActionButtons()}
            <button type="button" data-action="ok" class="primary" ${
              selectedTextUnit ? '' : 'disabled'
            }>Open workbench</button>
            <button type="button" data-action="close">Cancel</button>
          </footer>
        </section>
      </div>
    `;

    this.shadowRoot.querySelectorAll<HTMLElement>('[data-select-index]').forEach((element) => {
      element.addEventListener('click', () => {
        this.selectedIndex = Number(element.dataset.selectIndex ?? 0);
        this.render(true);
      });
    });

    this.shadowRoot.querySelectorAll<HTMLButtonElement>('[data-action]').forEach((button) => {
      button.addEventListener('click', () => {
        const action = button.dataset.action;
        const textUnit = this.getSelectedTextUnit();

        if (action === 'close') {
          this.hide();
        } else if (action === 'ok' && textUnit) {
          openWorkbench(textUnit);
        } else if (action?.startsWith('custom:') && textUnit) {
          const actionIndex = Number(action.slice('custom:'.length));
          const actionButton = config.actionButtons[actionIndex];
          if (actionButton) {
            openAction(textUnit, actionButton);
          }
        }
      });
    });
  }

  private renderTextUnits(): string {
    if (this.textUnits.length === 0) {
      return '<div class="item">No text unit metadata found.</div>';
    }

    return this.textUnits
      .map((textUnit, index) => {
        const selected = index === this.selectedIndex ? 'true' : 'false';
        return `
          <button type="button" class="item" data-select-index="${index}" data-selected="${selected}">
            <div class="meta">
              <span class="label">Locale</span>
              <span class="value">${escapeHtml(textUnit.locale)}</span>
              <span class="label">Text unit</span>
              <span class="value">${escapeHtml(textUnit.textUnitName)}</span>
              <span class="label">Repository</span>
              <span class="value">${escapeHtml(textUnit.repositoryName)}</span>
              <span class="label">Asset</span>
              <span class="value">${escapeHtml(textUnit.assetName)}</span>
              <span class="label">Type</span>
              <span class="value">${escapeHtml(textUnit.translationType)}</span>
            </div>
            <div class="variant">${escapeHtml(textUnit.textUnitVariant)}</div>
            ${textUnit.stack ? `<div class="stack">${escapeHtml(textUnit.stack)}</div>` : ''}
          </button>
        `;
      })
      .join('');
  }

  private renderActionButtons(): string {
    return config.actionButtons
      .map(
        (actionButton, index) =>
          `<button type="button" data-action="custom:${index}">${escapeHtml(actionButton.text)}</button>`,
      )
      .join('');
  }
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const modal = new IctModal();

chrome.storage.sync.get(DEFAULT_CONFIG, (items) => {
  config = normalizeConfig(items);
  activate();
});

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== 'sync') {
    return;
  }

  config = normalizeConfig({
    ...config,
    ...Object.fromEntries(
      Object.entries(changes).map(([key, change]) => [key, change.newValue]),
    ),
  });

  if (config.enabled) {
    activate();
    requestPendingMachineTranslations();
  }
  if (!config.mtShowErrors) {
    clearAllMachineTranslationErrors();
  }
});
