# Phase 02: Frontend Session & API Client Foundation - Pattern Map

**Mapped:** 2026-05-30
**Files analyzed:** 18
**Analogs found:** 18 / 18

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `../../vue/stock-v2/vue-app/src/services/apiClient.ts` | service/utility | request-response | `src/services/apiClient.ts` | exact |
| `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts` | test | request-response | `src/services/apiClient.test.ts` | exact |
| `../../vue/stock-v2/vue-app/src/services/authApi.ts` | service | request-response | `src/services/backtestApi.ts` | role-match |
| `../../vue/stock-v2/vue-app/src/services/authApi.test.ts` | test | request-response | `src/services/backtestApi.test.ts` | role-match |
| `../../vue/stock-v2/vue-app/src/services/authSession.ts` or `src/composables/useSession.ts` | store/hook | request-response/event-driven UI state | `src/composables/useAiAccessSettings.ts` | role-match |
| `../../vue/stock-v2/vue-app/src/services/authSession.test.ts` | test | request-response/event-driven UI state | `src/composables/useAiAccessSettings.test.ts` | role-match |
| `../../vue/stock-v2/vue-app/src/services/apiTypes.ts` | model | transform | `src/services/apiTypes.ts` | exact |
| `../../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts` | config/utility | transform | `src/services/runtimeDataMode.ts` | exact |
| `../../vue/stock-v2/vue-app/src/services/runtimeDataMode.test.ts` | test | transform | `src/services/runtimeDataMode.test.ts` | exact |
| `../../vue/stock-v2/vue-app/src/services/pageApiClients.ts` | provider | transform/request-response | `src/services/pageApiClients.ts` | exact |
| `../../vue/stock-v2/vue-app/src/services/backtestApi.ts` | service | request-response/CRUD | `src/services/backtestApi.ts` | exact |
| `../../vue/stock-v2/vue-app/src/services/opsApi.ts` | service | request-response/CRUD | `src/services/opsApi.ts` | exact |
| `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts` | service | request-response/CRUD | `src/services/aiAccessApi.ts` | exact |
| `../../vue/stock-v2/vue-app/src/App.vue` | component/provider | event-driven/request-response | `src/App.vue` | exact |
| `../../vue/stock-v2/vue-app/src/App.test.ts` | test | event-driven/request-response | `src/api-adapter-wiring.test.ts` | role-match |
| `../../vue/stock-v2/vue-app/src/components/AuthPanel.vue` or equivalent | component | event-driven/request-response | `src/pages/Settings.vue` | role-match |
| `../../vue/stock-v2/vue-app/src/components/SessionBanner.vue` or equivalent | component | event-driven | `src/components/Toast.vue` + `src/components/Header.vue` | role-match |
| `../../vue/stock-v2/vue-app/src/i18n.ts` | config/model | transform | `src/i18n.ts` | exact |

## Pattern Assignments

### `src/services/apiClient.ts` (service/utility, request-response)

**Analog:** `../../vue/stock-v2/vue-app/src/services/apiClient.ts`

**Imports and typed error pattern** (lines 1-30):
```typescript
import type { ApiFailure, ApiSuccess } from './apiTypes';

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | null;
  readonly field?: string;
  readonly details?: Record<string, unknown>;
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  json?: unknown;
}
```

**Query and envelope guards** (lines 32-54):
```typescript
export function buildQueryString(params: Record<string, string | number | boolean | null | undefined>): string {
  const pairs = Object.entries(params)
    .filter(([, value]) => value !== null && value !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
  return pairs.length ? `?${pairs.join('&')}` : '';
}

function isApiFailure(value: unknown): value is ApiFailure {
  const error = isRecord(value) ? value.error : null;
  return !!value
    && typeof value === 'object'
    && isRecord(error)
    && typeof error.code === 'string'
    && typeof error.message === 'string';
}
```

**Core request pattern** (lines 71-118):
```typescript
export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { json, headers: headersInit, ...requestOptions } = options;
  const headers = new Headers(headersInit);
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');

  const init: RequestInit = { ...requestOptions, headers };
  if (json !== undefined) {
    if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    init.body = JSON.stringify(json);
  }

  const response = await fetch(path, init);
  const payload = await readJson(response);
  if (!response.ok) { /* throw ApiClientError from envelope */ }
  if (!isApiSuccess<T>(payload)) { /* throw INVALID_API_RESPONSE */ }
  return payload.data;
}
```

**Required Phase 2 extension:** keep this as the single boundary, then add credentials default, `apiPaginatedRequest`, `meta.traceId` parsing, CSRF bootstrap/header, single-flight refresh/replay, and session failure callbacks here. Do not put CSRF/refresh logic in domain adapters.

---

### `src/services/apiClient.test.ts` (test, request-response)

**Analog:** `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts`

**Vitest setup and fetch inspection helpers** (lines 1-27):
```typescript
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiClientError, apiRequest, buildQueryString } from './apiClient';

afterEach(() => {
  vi.unstubAllGlobals();
});

function lastFetchInit(): RequestInit {
  const calls = vi.mocked(fetch).mock.calls;
  return calls[calls.length - 1][1] as RequestInit;
}
```

**Success/json/header tests** (lines 35-58):
```typescript
vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
  data: { created: true },
  requestId: 'req_json',
}), { status: 200, headers: { 'Content-Type': 'application/json' } })));

await expect(apiRequest<{ created: boolean }>('/api/v1/example', {
  method: 'POST',
  json: { symbol: 'AAPL' },
})).resolves.toEqual({ created: true });

expect(lastFetchInit().body).toBe(JSON.stringify({ symbol: 'AAPL' }));
expect(headerValue('content-type')).toBe('application/json');
```

**Error envelope tests** (lines 82-95):
```typescript
vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
  error: { code: 'OPS_PERMISSION_DENIED', message: 'Forbidden' },
  requestId: 'req_2',
}), { status: 403, headers: { 'Content-Type': 'application/json' } })));

await expect(apiRequest('/api/v1/ops/jobs')).rejects.toMatchObject({
  name: 'ApiClientError',
  status: 403,
  code: 'OPS_PERMISSION_DENIED',
  requestId: 'req_2',
});
```

**Apply to Phase 2 tests:** add red tests in this same style for `credentials: "include"`, `meta.traceId`, paginated helper, CSRF cookie/header, `AUTH_CSRF_TOKEN_INVALID`, one retry max, refresh failure, replay 401, and parallel 401 single-flight.

---

### `src/services/authApi.ts` (service, request-response)

**Analog:** `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`

**Interface + factory shape** (lines 14-21, 263-276):
```typescript
export interface BacktestApi {
  mode: RuntimeDataMode;
  createRun(request: BacktestRunRequest): Promise<BacktestRunDto>;
  validateStrategy(request: StrategyValidationRequest): Promise<StrategyValidationDto>;
  getRun(runId: string): Promise<BacktestRunDto>;
}

export function createHttpBacktestApi(basePath = '/api/v1'): BacktestApi {
  return {
    mode: 'api',
    createRun: request => apiRequest(`${basePath}/backtests/runs`, { method: 'POST', json: request }),
    getRun: runId => apiRequest(`${basePath}/backtests/runs/${encodeURIComponent(runId)}`),
  };
}

export function createBacktestApi(mode: RuntimeDataMode, basePath = '/api/v1'): BacktestApi {
  return mode === 'api' ? createHttpBacktestApi(basePath) : createMockBacktestApi();
}
```

**Auth-specific assignment:** create a small `AuthApi` interface with `register`, `login`, `refresh`, `logout`, `me`, and `csrf`. Use `apiRequest` for envelope unwrapping. Return browser session/user metadata only; no access/refresh token fields.

**Mock adapter safety pattern** (from `aiAccessApi.ts` lines 30-40):
```typescript
it('mock adapter manages keys without returning raw secrets', async () => {
  const serialized = JSON.stringify({ created, keys });
  expect(serialized).not.toContain(createTradeKeyRequest.apiKey);
  expect(serialized).not.toContain(createTradeKeyRequest.apiSecret);
});
```

Apply the same non-leak assertion to auth session data: serialized auth state must not contain `accessToken`, `refreshToken`, cookie values, or password.

---

### `src/services/authSession.ts` or `src/composables/useSession.ts` (store/hook, request-response + event-driven UI state)

**Analog:** `../../vue/stock-v2/vue-app/src/composables/useAiAccessSettings.ts`

**Composable import and dependency injection pattern** (lines 1-18, 74-78, 110-117):
```typescript
import { computed, reactive, ref } from 'vue';
import type { AiAccessApi } from '../services/aiAccessApi';
import { getRuntimeApiClients } from '../services/pageApiClients';

interface UseAiAccessSettingsOptions {
  api?: AiAccessApi;
  lang: () => Lang;
  emitToast: (message: string) => void;
}

export function useAiAccessSettings(options: UseAiAccessSettingsOptions) {
  const api = options.api ?? getRuntimeApiClients().aiAccess;
  const keys = reactive<ApiKeyView[]>([]);
  const readKeys = computed(() => keys.filter(k => k.permissions === 'read'));
  const copiedId = ref<string | null>(null);
}
```

**Load/error/reset pattern** (lines 283-300):
```typescript
async function loadAiAccessData() {
  try {
    const [nextKeys, nextEndpoints, nextAgents] = await Promise.all([
      api.listKeys(),
      api.listMcpEndpoints(),
      api.listAgents(),
    ]);
    replaceKeys(nextKeys);
    mcpServers.splice(0, mcpServers.length, ...nextEndpoints.map(endpointFromDto));
    agents.splice(0, agents.length, ...nextAgents.map(agentFromDto));
    await refreshAuditCalls();
  } catch (error) {
    replaceKeys([]);
    mcpServers.splice(0, mcpServers.length);
    agents.splice(0, agents.length);
    calls.splice(0, calls.length);
    options.emitToast(errorMessage(error, options.lang() === 'zh' ? 'AI Access 載入失敗' : 'AI Access load failed'));
  }
}
```

**Session assignment:** model explicit states `checking`, `authenticated`, `anonymous`, `refreshing`, `error`; expose actions `restore`, `login`, `register`, `logout`, `markRefreshing`, `handleSessionError`. Keep only user metadata and expiry timestamps in reactive state.

---

### `src/services/runtimeDataMode.ts` (config/utility, transform)

**Analog:** `../../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`

**Current mode normalization** (lines 1-9):
```typescript
import type { RuntimeDataMode } from './apiTypes';

export function normalizeRuntimeDataMode(value: unknown): RuntimeDataMode {
  return value === 'api' ? 'api' : 'mock';
}

export function getRuntimeDataMode(): RuntimeDataMode {
  return normalizeRuntimeDataMode(import.meta.env.VITE_DATA_MODE);
}
```

**Required Phase 2 extension:** preserve unset/empty local default to `mock`, accept explicit `mock` and `api`, and throw a typed fail-fast error for any other explicit value. Existing tests are lines 4-14 in `runtimeDataMode.test.ts`; update the first test because `local` must no longer silently become mock.

---

### `src/services/pageApiClients.ts` (provider, transform/request-response)

**Analog:** `../../vue/stock-v2/vue-app/src/services/pageApiClients.ts`

**Registry/memoization pattern** (lines 1-33):
```typescript
import { createAiAccessApi, type AiAccessApi } from './aiAccessApi';
import { createBacktestApi, type BacktestApi } from './backtestApi';
import { createOpsApi, type OpsApi } from './opsApi';
import type { RuntimeDataMode } from './apiTypes';
import { getRuntimeDataMode } from './runtimeDataMode';

interface RuntimeApiClients {
  mode: RuntimeDataMode;
  basePath: string;
  aiAccess: AiAccessApi;
  backtest: BacktestApi;
  ops: OpsApi;
}

let clients: RuntimeApiClients | null = null;

export function getRuntimeApiClients(basePath = '/api/v1'): RuntimeApiClients {
  const mode = getRuntimeDataMode();
  if (!clients || clients.mode !== mode || clients.basePath !== basePath) {
    clients = {
      mode,
      basePath,
      aiAccess: createAiAccessApi(mode, basePath),
      backtest: createBacktestApi(mode, basePath),
      ops: createOpsApi(mode, basePath),
    };
  }
  return clients;
}

export function resetRuntimeApiClientsForTests() {
  clients = null;
}
```

**Apply to Phase 2:** add `auth: createAuthApi(mode, basePath)` only if auth needs registry access. Keep `resetRuntimeApiClientsForTests()` and use it from test cleanup.

---

### `src/services/backtestApi.ts`, `src/services/opsApi.ts`, `src/services/aiAccessApi.ts` (services, request-response/CRUD)

**Analog:** existing files themselves.

**HTTP adapter pattern to preserve** (`backtestApi.ts` lines 263-271):
```typescript
export function createHttpBacktestApi(basePath = '/api/v1'): BacktestApi {
  return {
    mode: 'api',
    createRun: request => apiRequest(`${basePath}/backtests/runs`, { method: 'POST', json: request }),
    validateStrategy: request => apiRequest(`${basePath}/backtests/strategies/validate`, { method: 'POST', json: request }),
    getRun: runId => apiRequest(`${basePath}/backtests/runs/${encodeURIComponent(runId)}`),
    getResult: runId => apiRequest(`${basePath}/backtests/runs/${encodeURIComponent(runId)}/result`),
    listRuns: params => apiPaginatedRequest(`${basePath}/backtests/runs${buildQueryString(params ?? {})}`),
  };
}
```

**Duplicated paginated helper to move into shared client** (`opsApi.ts` lines 86-122):
```typescript
async function apiPaginatedRequest<T>(path: string): Promise<PaginatedResponse<T>> {
  const headers = new Headers();
  headers.set('Accept', 'application/json');
  const response = await fetch(path, { headers });
  const payload = await readJson(response);
  if (!response.ok) { /* ApiClientError */ }
  if (!isPaginatedResponse<T>(payload)) { /* INVALID_API_RESPONSE */ }
  return payload;
}
```

**Mock clone/non-mutation pattern** (`aiAccessApi.ts` lines 49-67):
```typescript
function cloneKey(key: AiAccessKeyDto): AiAccessKeyDto {
  return {
    ...key,
    riskLimits: key.riskLimits
      ? { ...key.riskLimits, allowedSymbols: [...key.riskLimits.allowedSymbols] }
      : undefined,
  };
}
```

**Required Phase 2 adapter cleanup:** delete local `apiPaginatedRequest`, `readJson`, `isApiFailure`, and `isPaginatedResponse` duplicates from these adapters after shared `apiClient.ts` provides the paginated helper. Domain adapters should only build typed paths/payloads and call shared client helpers.

---

### `src/App.vue` (component/provider, event-driven/request-response)

**Analog:** `../../vue/stock-v2/vue-app/src/App.vue`

**Shell and overlay mounting pattern** (lines 1-31):
```vue
<template>
  <div class="root">
    <AppHeader :page="page" :lang="tweaks.lang" :admin="tweaks.admin" @navigate="page = $event" @open-cmdk="cmdk = true" />
    <main class="main">
      <Overview v-if="page === 'overview'" :lang="tweaks.lang" @order="openTicket" @navigate="page = $event" />
      <Settings v-else-if="page === 'settings'" :lang="tweaks.lang" :theme="tweaks.theme" @set-tweak="onSettingsTweak" @toast="showToast" />
      <Backtest v-else-if="page === 'backtest'" :lang="tweaks.lang" />
      <Ops v-else-if="page === 'ops'" :lang="tweaks.lang" @toast="showToast" />
    </main>
    <Toast :msg="toast" />
    <TweaksPanel :tweaks="tweaks" @set="onTweaksPanelSet" />
  </div>
</template>
```

**Toast timer lifecycle pattern** (lines 59-80, 124-128):
```typescript
const toast = ref('');
let toastTimer: ReturnType<typeof setTimeout> | null = null;

function showToast(m: string) {
  if (toastTimer) clearTimeout(toastTimer);
  toast.value = m;
  toastTimer = setTimeout(() => {
    toast.value = '';
    toastTimer = null;
  }, 1800);
}

onBeforeUnmount(() => {
  if (!toastTimer) return;
  clearTimeout(toastTimer);
  toastTimer = null;
});
```

**Apply to Phase 2:** mount session initialization and global banner in the shell without replacing page routing. Pass session metadata/actions into `Header` or a shell-mounted `SessionBanner`. Keep existing `Toast` for short non-blocking events only.

---

### `src/components/Header.vue` (component, event-driven)

**Analog:** `../../vue/stock-v2/vue-app/src/components/Header.vue`

**Props/emits/computed navigation pattern** (lines 48-75):
```typescript
import { computed, ref } from 'vue';
import { t } from '../i18n';
import type { Lang, Page } from '../types';

const props = defineProps<{ page: Page; lang: Lang; admin: boolean }>();
defineEmits<{ navigate: [p: Page]; 'open-cmdk': [] }>();

const bellOpen = ref(false);
const navItems = computed(() => {
  const base: { k: Page; l: string }[] = [
    { k: 'overview', l: t(props.lang, 'overview') },
    { k: 'settings', l: t(props.lang, 'settings') },
  ];
  if (props.admin) base.push({ k: 'ops', l: t(props.lang, 'ops') });
  return base;
});
```

**Header dimensions and status target** (lines 79-137):
```css
.hdr {
  display: flex; align-items: center; gap: 24px;
  height: 60px; padding: 0 22px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}
.icon-btn { width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; }
.avatar { width: 32px; height: 32px; border-radius: 50%; }
```

**Apply to Phase 2:** add compact session indicator near the existing search/avatar area while preserving 60px header height and 32px avatar. Do not squeeze error details into header; render details in banner.

---

### `src/components/AuthPanel.vue` or equivalent (component, event-driven/request-response)

**Analog:** `../../vue/stock-v2/vue-app/src/pages/Settings.vue`

**Form/modal/control pattern** (lines 137-183):
```vue
<div v-if="addOpen" class="modal-bg" @click.self="addOpen = false">
  <div class="modal">
    <div class="modal-h">
      <div style="font-size:15px;font-weight:600">
        {{ addMode === 'trade' ? t(lang, 'addBroker') : t(lang, 'addKey') }}
      </div>
      <button class="x" @click="addOpen = false">×</button>
    </div>
    <div class="modal-b">
      <div class="form-row">
        <label>{{ t(lang, 'apiKey') }}</label>
        <input class="inp mono" v-model="draft.key" placeholder="sk_live_...">
      </div>
    </div>
    <div class="modal-f">
      <button class="btn-ghost" @click="addOpen = false">{{ t(lang, 'cancel') }}</button>
      <button class="btn-accent" :disabled="!draft.key" @click="saveKey()">{{ t(lang, 'save') }}</button>
    </div>
  </div>
</div>
```

**Segmented control and input styles** (lines 337-353):
```css
.seg-pill { display: inline-flex; gap: 4px; background: var(--surface2); padding: 3px; border-radius: 6px; }
.seg-pill button {
  padding: 5px 12px; background: transparent; color: var(--fg-dim); border: 0;
  border-radius: 4px; font-size: 12px; cursor: pointer; font-family: inherit;
}
.seg-pill button.active { background: var(--surface); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.06); }
.inp {
  width: 100%; background: var(--surface2); border: 1px solid var(--border);
  border-radius: 6px; padding: 7px 10px; font-size: 13px; color: var(--fg);
}
.inp:focus { outline: 0; border-color: var(--accent); }
```

**Apply to Phase 2:** use one constrained auth panel, visible labels, `autocomplete`, stable button sizes, login/register segmented tabs, and no token/cookie display.

---

### `src/components/SessionBanner.vue` or equivalent (component, event-driven)

**Analog:** `../../vue/stock-v2/vue-app/src/components/Toast.vue`

**Transition/status-dot pattern** (lines 1-27):
```vue
<Transition name="toast">
  <div v-if="msg" class="toast">
    <span class="dot" />
    {{ msg }}
  </div>
</Transition>
```

```css
.toast {
  position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
  background: var(--fg); color: var(--bg);
  padding: 10px 18px; border-radius: 99px;
  font-size: 13px; font-weight: 500;
  display: flex; align-items: center; gap: 10px;
}
.dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); }
```

**Apply to Phase 2:** use the same transition/dot vocabulary, but implement blocking session/security issues as an inline/global banner below the header, not as a disappearing toast. Include `aria-live`, code/status/trace-id details, and retry/sign-in actions.

---

### `src/i18n.ts` (config/model, transform)

**Analog:** `../../vue/stock-v2/vue-app/src/i18n.ts`

**Translation map pattern** (lines 1-5, 107-111, 211):
```typescript
import type { Lang } from './types';

export const I18N: Record<Lang, Record<string, string>> = {
  zh: {
    overview: '總覽',
  },
  en: {
    overview: 'Overview',
  },
};

export const t = (lang: Lang, k: string): string => I18N[lang]?.[k] ?? k;
```

**Apply to Phase 2:** add all auth/session strings in both `zh` and `en`. Use `t(lang, key)` from components; avoid new inline-only strings except temporary diagnostic composition.

---

### `src/App.test.ts` and component/session tests (test, event-driven/request-response)

**Analog:** `../../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts` and `src/testUtils.ts`

**Mount/test utility pattern** (`testUtils.ts` lines 9-27, 50-58, 60-77):
```typescript
export function mountWithPinia(component: Component, props: Record<string, unknown> = {}) {
  const el = document.createElement('div');
  document.body.appendChild(el);
  const pinia = createPinia();
  setActivePinia(pinia);
  const app = createApp(component, props);
  app.use(pinia);
  app.mount(el);
  mounted.push(cleanup);
  return cleanup;
}

export function cleanupMounted() {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  unmountAll();
  document.body.innerHTML = '';
  resetRuntimeApiClientsForTests();
}

export async function clickButton(text: string) {
  buttonByText(text).dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await nextTick();
}
```

**API-mode failure wiring pattern** (`api-adapter-wiring.test.ts` lines 78-91, 119-132):
```typescript
vi.stubEnv('VITE_DATA_MODE', 'api');
vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
  error: { code: 'OPS_UNAVAILABLE', message: 'Ops unavailable' },
  requestId: 'req_ops_down',
}), { status: 503, headers: { 'Content-Type': 'application/json' } })));
const toasts: string[] = [];

mountWithPinia(Ops, { lang: 'en', onToast: (message: string) => toasts.push(message) });
await flushAsync();

expect(toasts).toContain('Ops unavailable');
expect(document.body.textContent).not.toContain('Refetch news');
```

**Apply to Phase 2:** create `App.test.ts`, `authSession.test.ts`, and `authApi.test.ts` using these helpers. Stub env/fetch, mount shell, flush async, click auth buttons, and assert no stale authenticated UI after 401/refresh failure.

## Shared Patterns

### API Envelope And Errors

**Source:** `src/services/apiClient.ts` lines 43-54 and 89-118  
**Apply to:** all HTTP services and auth/session flows.

Use `ApiClientError` for non-OK responses, invalid JSON, invalid success envelopes, invalid paginated envelopes, CSRF errors, and refresh failures. Preserve `status`, `code`, `message`, `field`, `details`, and request id. Phase 2 must expand request id extraction to `payload.meta.traceId ?? payload.requestId ?? null`.

### Runtime Mode

**Source:** `src/services/pageApiClients.ts` lines 17-33 and `src/services/runtimeDataMode.ts` lines 3-9  
**Apply to:** `authApi`, `authSession`, App boot, adapter wiring tests.

Mock and API stay behind one mode gate. API-mode failures must surface as errors; they must not fall back to mock.

### Typed Adapter Factories

**Source:** `src/services/backtestApi.ts` lines 263-276, `src/services/opsApi.ts` lines 225-246, `src/services/aiAccessApi.ts` lines 400-419  
**Apply to:** `authApi.ts` and existing adapter cleanup.

Keep `createMock*Api`, `createHttp*Api`, and `create*Api(mode, basePath)` factories. HTTP methods build URLs and payloads only; shared `apiClient` owns transport.

### UI Shell And Global Feedback

**Source:** `src/App.vue` lines 1-31 and 73-80; `src/components/Toast.vue` lines 1-27; `src/components/Header.vue` lines 79-137  
**Apply to:** `App.vue`, `Header.vue`, `SessionBanner.vue`, `AuthPanel.vue`.

Mount global session UI in the existing shell. Keep the page router and overlays. Use toast for short non-blocking events; use a persistent banner for blocking auth/security failures.

### Tests And Cleanup

**Source:** `src/testUtils.ts` lines 50-58 and `src/api-adapter-wiring.test.ts` lines 78-91  
**Apply to:** all new Phase 2 frontend tests.

Always clean globals/envs/timers and reset runtime clients. Use `vi.stubEnv`, `vi.stubGlobal('fetch', ...)`, `mountWithPinia`, `flushAsync`, and DOM text/button helpers.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| None | — | — | Every planned file has an exact or role-match analog in the sibling Vue app. |

## Metadata

**Analog search scope:** `../../vue/stock-v2/vue-app/src/services`, `src/composables`, `src/components`, `src/pages`, `src/*.test.ts`, `src/i18n.ts`, `src/testUtils.ts`  
**Files scanned:** 20 primary files plus codebase maps and phase artifacts  
**Pattern extraction date:** 2026-05-30
