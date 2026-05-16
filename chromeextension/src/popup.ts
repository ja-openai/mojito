export {};

type IctConfig = {
  mojitoBaseUrl: string;
  enabled: boolean;
  headerName: string;
  headerValue: string;
  actionButtons: { text: string; url: string }[];
  mtEnabled: boolean;
  mtShowErrors: boolean;
  mtEndpointUrlFormat: string;
};

const DEFAULT_MT_ENDPOINT_URL_FORMAT =
  'http://localhost:5173/api/machine-translation';
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

function getInputElement(id: string): HTMLInputElement {
  const element = document.getElementById(id);
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`Missing input: ${id}`);
  }
  return element;
}

const controls = {
  enabled: getInputElement('enabled'),
  mtEnabled: getInputElement('mtEnabled'),
  mtShowErrors: getInputElement('mtShowErrors'),
  mojitoBaseUrl: getInputElement('mojitoBaseUrl'),
  headerName: getInputElement('headerName'),
  headerValue: getInputElement('headerValue'),
  mtEndpointUrlFormat: getInputElement('mtEndpointUrlFormat'),
};

const statusElement = document.getElementById('status');
let currentConfig = DEFAULT_CONFIG;

function normalizeConfig(value: Partial<IctConfig>): IctConfig {
  const config = {
    ...DEFAULT_CONFIG,
    ...value,
    actionButtons: Array.isArray(value.actionButtons) ? value.actionButtons : [],
  };

  config.mtEndpointUrlFormat = DEFAULT_MT_ENDPOINT_URL_FORMAT;
  if (!config.mojitoBaseUrl.trim() || config.mojitoBaseUrl === OLD_DEFAULT_MOJITO_BASE_URL) {
    config.mojitoBaseUrl = DEFAULT_MOJITO_BASE_URL;
  }
  if (config.mtEnabled) {
    config.enabled = true;
  }

  return config;
}

function showStatus(message: string): void {
  if (!statusElement) {
    return;
  }

  statusElement.textContent = message;
  window.setTimeout(() => {
    if (statusElement.textContent === message) {
      statusElement.textContent = '';
    }
  }, 1600);
}

function render(config: IctConfig): void {
  currentConfig = config;
  controls.enabled.checked = config.enabled;
  controls.mtEnabled.checked = config.mtEnabled;
  controls.mtShowErrors.checked = config.mtShowErrors;
  controls.mojitoBaseUrl.value = config.mojitoBaseUrl;
  controls.headerName.value = config.headerName;
  controls.headerValue.value = config.headerValue;
  controls.mtEndpointUrlFormat.value = config.mtEndpointUrlFormat;
}

function saveConfigPatch(patch: Partial<IctConfig>): void {
  chrome.storage.sync.set(normalizeConfig({ ...getCurrentConfigFromControls(), ...patch }), () => {
    showStatus('Saved');
  });
}

function getCurrentConfigFromControls(): IctConfig {
  return {
    ...currentConfig,
    enabled: controls.enabled.checked,
    mtEnabled: controls.mtEnabled.checked,
    mtShowErrors: controls.mtShowErrors.checked,
    mojitoBaseUrl: controls.mojitoBaseUrl.value.trim(),
    headerName: controls.headerName.value.trim(),
    headerValue: controls.headerValue.value,
    mtEndpointUrlFormat: DEFAULT_MT_ENDPOINT_URL_FORMAT,
  };
}

function loadConfig(): void {
  chrome.storage.sync.get(DEFAULT_CONFIG, (items) => {
    const config = normalizeConfig(items);
    chrome.storage.sync.set(config);
    render(config);
  });
}

controls.enabled.addEventListener('change', () => {
  if (controls.enabled.checked) {
    saveConfigPatch({ enabled: true });
    return;
  }

  controls.mtEnabled.checked = false;
  saveConfigPatch({ enabled: false, mtEnabled: false });
});

controls.mtEnabled.addEventListener('change', () => {
  if (controls.mtEnabled.checked) {
    controls.enabled.checked = true;
    saveConfigPatch({ enabled: true, mtEnabled: true });
    return;
  }

  saveConfigPatch({ mtEnabled: false });
});

controls.mtShowErrors.addEventListener('change', () => {
  saveConfigPatch({ mtShowErrors: controls.mtShowErrors.checked });
});

controls.mojitoBaseUrl.addEventListener('input', () => {
  saveConfigPatch({ mojitoBaseUrl: controls.mojitoBaseUrl.value.trim() });
});

controls.headerName.addEventListener('input', () => {
  saveConfigPatch({ headerName: controls.headerName.value.trim() });
});

controls.headerValue.addEventListener('input', () => {
  saveConfigPatch({ headerValue: controls.headerValue.value });
});

loadConfig();
