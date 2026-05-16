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

type MachineTranslationRequest = {
  textUnitVariant?: string;
  locale?: string;
};

type MachineTranslationResponse = {
  data: string;
  error?: string;
};

type MojitoMachineTranslationResponse = {
  text?: string;
  data?: string;
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

const MAIN_FRAME_HEADER_RULE_ID = 1;
const MOJITO_API_HEADER_RULE_ID = 2;
const HEADER_RULE_IDS = [MAIN_FRAME_HEADER_RULE_ID, MOJITO_API_HEADER_RULE_ID];

let backgroundConfig = DEFAULT_CONFIG;

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

function getMojitoUrlFilter(mojitoBaseUrl: string): string | null {
  try {
    const url = new URL(mojitoBaseUrl);
    return `${url.protocol}//${url.host}/`;
  } catch {
    return null;
  }
}

function getModifyHeaderAction(): chrome.declarativeNetRequest.RuleAction {
  return {
    type: chrome.declarativeNetRequest.RuleActionType.MODIFY_HEADERS,
    requestHeaders: [
      {
        header: backgroundConfig.headerName,
        operation: chrome.declarativeNetRequest.HeaderOperation.SET,
        value: backgroundConfig.headerValue,
      },
    ],
  };
}

function buildHeaderRules(): chrome.declarativeNetRequest.Rule[] {
  const action = getModifyHeaderAction();
  const rules: chrome.declarativeNetRequest.Rule[] = [
    {
      id: MAIN_FRAME_HEADER_RULE_ID,
      priority: 1,
      action,
      condition: {
        urlFilter: '*',
        resourceTypes: [chrome.declarativeNetRequest.ResourceType.MAIN_FRAME],
      },
    },
  ];

  const mojitoUrlFilter = getMojitoUrlFilter(backgroundConfig.mojitoBaseUrl);
  if (mojitoUrlFilter) {
    rules.push({
      id: MOJITO_API_HEADER_RULE_ID,
      priority: 1,
      action,
      condition: {
        urlFilter: mojitoUrlFilter,
        resourceTypes: [chrome.declarativeNetRequest.ResourceType.XMLHTTPREQUEST],
      },
    });
  }

  return rules;
}

function updateHeaderRules(): Promise<void> {
  return new Promise((resolve, reject) => {
    chrome.declarativeNetRequest.updateDynamicRules(
      {
        addRules: backgroundConfig.enabled ? buildHeaderRules() : [],
        removeRuleIds: HEADER_RULE_IDS,
      },
      () => {
        const error = chrome.runtime.lastError;
        if (error) {
          reject(new Error(error.message));
          return;
        }
        resolve();
      },
    );
  });
}

function persistInitialConfig(): void {
  chrome.storage.sync.get(DEFAULT_CONFIG, (items) => {
    backgroundConfig = normalizeConfig(items);
    chrome.storage.sync.set(backgroundConfig);
    void updateHeaderRules().catch((error: unknown) => {
      console.warn('Unable to update Mojito ICT header rules', error);
    });
  });
}

function onConfigChanged(changes: Record<string, chrome.storage.StorageChange>): void {
  let shouldUpdateHeaderRules = false;

  for (const [key, change] of Object.entries(changes)) {
    if (!(key in DEFAULT_CONFIG)) {
      continue;
    }

    backgroundConfig = normalizeConfig({
      ...backgroundConfig,
      [key]: change.newValue,
    });

    if (
      key === 'enabled' ||
      key === 'headerName' ||
      key === 'headerValue' ||
      key === 'mojitoBaseUrl'
    ) {
      shouldUpdateHeaderRules = true;
    }
  }

  if (shouldUpdateHeaderRules) {
    void updateHeaderRules().catch((error: unknown) => {
      console.warn('Unable to update Mojito ICT header rules', error);
    });
  }
}

function fetchMachineTranslation(
  request: MachineTranslationRequest,
): Promise<MachineTranslationResponse> {
  return fetch(backgroundConfig.mtEndpointUrlFormat, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      textSource: request.textUnitVariant,
      sourceBcp47Tag: 'en',
      targetBcp47Tag: request.locale,
      skipFunctionalProtection: false,
      skipLeveraging: true,
    }),
  })
    .then(checkMachineTranslationResponse)
    .then(toMachineTranslationResponse);
}

function checkMachineTranslationResponse(response: Response): Promise<MojitoMachineTranslationResponse> {
  if (!response.ok) {
    throw new Error(`MT request failed with status ${response.status} for ${response.url}`);
  }

  return response.json() as Promise<MojitoMachineTranslationResponse>;
}

function toMachineTranslationResponse(
  response: MojitoMachineTranslationResponse,
): MachineTranslationResponse {
  return { data: response.data ?? response.text ?? '' };
}

function handleMachineTranslationRequest(
  request: MachineTranslationRequest,
  sendResponse: (response: MachineTranslationResponse) => void,
): boolean {
  if (!backgroundConfig.mtEndpointUrlFormat || !request.textUnitVariant || !request.locale) {
    sendResponse({ data: '' });
    return false;
  }

  fetchMachineTranslation(request)
    .then(sendResponse)
    .catch((error: unknown) => {
      const message = error instanceof Error ? error.message : String(error);
      console.warn('Mojito ICT MT request failed', message);
      sendResponse({ data: '', error: message });
    });

  return true;
}

persistInitialConfig();

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName === 'sync') {
    onConfigChanged(changes);
  }
});

chrome.runtime.onMessage.addListener((request: MachineTranslationRequest, _sender, sendResponse) =>
  handleMachineTranslationRequest(request, sendResponse),
);
