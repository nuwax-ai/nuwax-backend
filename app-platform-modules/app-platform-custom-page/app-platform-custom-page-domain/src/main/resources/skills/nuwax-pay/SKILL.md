---
name: nuwax-pay
description: "Payment integration API for projects. Provides three modes: 1) Cashier mode — hosted cashier URL; 2) H5 mode — custom UI in mobile/system browser (NOT in App WebView); 3) App native mode — JSBridge + native SDK inside App WebView only. Also supports querying payment status with channel sync. Use this skill whenever the user wants to add payment/checkout/purchase functionality to a project. Keywords: payment, pay, cashier, checkout, H5 pay, App pay, native pay, WeChat pay, Alipay pay, order, 支付, 收银台, App支付, 原生支付, 下单, 微信支付, 支付宝支付."
license: MIT
---

# Nuwax Pay Skill

## Overview

This skill (`@nuwax-pay`) provides REST endpoints to integrate payment into your projects. The frontend must pass `projectId` (sourced from the `DEV_PROJECT_ID` env var — see [Frontend pitfalls](#frontend-pitfalls--read-first) for how to get it into the browser) plus the order details and amount. No login/tenant info is needed in the request body — the browser session identifies the tenant.

Use this skill when the user's project needs to collect money. Pick the integration mode by **client context** (pitfall #7), then use status query to confirm payment:

| Mode | When to use | Client | Effort |
|------|-------------|--------|--------|
| **Cashier mode** (`/api/pay/general/cashier`) | Default choice. Hosted cashier page. **iframe dev:** `window.open` + poll in place. **prod:** `location.href` + return URL. | Any (App / browser / desktop) | Lowest — one API call + open cashier. |
| **App native mode** (`/app/create-order` → `/app/pay`) | **Only inside App WebView**. WxPay: `redirectUrl` + `launchMiniProgram`; AliPay: open `redirectUrl`. Poll `/status`. | **App WebView only** — backend rejects non-App callers (`9384`) | Medium — two API calls + `invokeAppPay`. |
| **H5 mode** (`/h5/create-order` → `/h5/pay`) | Custom payment UI in **mobile/system browser** (Safari, Chrome, WeChat built-in browser is separate). Two-step: create order, then invoke channel. | **Mobile/system browser only** — **never** in App WebView (backend rejects with `9383`) | Medium — two API calls + handle formHtml/redirectUrl. |
| **Status query** (`/api/pay/general/status`) | After payment, poll the order status (all modes). | Any | One API call. |

> **Client routing (MANDATORY — pitfall #7):** App WebView → **App API only** (`/app/*`). Phone/system browser → **H5 API only** (`/h5/*`) or cashier. Never call H5 from App or App from browser — the backend enforces this.

> **Order numbers**: the backend generates business order numbers based on `projectId` (required, from `DEV_PROJECT_ID`):
> - If you pass `bizOrderNo`: `GP1_{projectId}_{bizOrderNo}` — idempotent, safe for retries.
> - If you omit `bizOrderNo`: `GP2_{projectId}_{timestamp}_{random}` — auto-generated, always unique.

> **Payment is asynchronous — status polling is mandatory.** The user pays on the cashier/channel page, which is out of your control. When the browser returns to your result page (via `frontNotifyUrl`), the payment result may not be in the backend yet (and the user may not return at all). **You MUST poll `POST /api/pay/general/status` on the return page until `status === "PAID"` (or `FAILED`/`CLOSED`).** Polling `/status` is the only reliable source of truth for payment success — never trust that "the user came back" means "paid".

### Return URL behavior by mode

| Context | Open cashier | Wait for result | `frontNotifyUrl` role |
|---------|--------------|-----------------|------------------------|
| **Dev iframe preview** (`window.self !== window.top`) | `window.open(cashierUrl)` — **never** `location.href` in iframe | **Stay on pay page**, poll `/status` until terminal; show result **in place** | Passed to API for the **popup window's** post-pay redirect only — the iframe page **does not** rely on this URL coming back |
| **Standalone / published prod** | `window.location.href = cashierUrl` | Cashier redirects to `frontNotifyUrl` → result page polls `/status` | Required — drives the return-redirect flow (pitfall #2) |

> **This split is mandatory (pitfall #6).** Using `location.href` inside a dev iframe causes cross-origin cashier round-trips → blank page / lost order. The iframe path avoids leaving the iframe entirely.

Both modes end with **`gatewayOrderNo` from storage**, not from the return URL (pitfall #3). H5 relay behavior unchanged — see H5 section for iframe vs standalone channel launch.

---

## 🚨 Implementation iron rules — read these BEFORE writing a single line of payment code

These are the seven things that, when skipped or implemented loosely, are responsible for **every** "支付成功后跳转空白页 / 订单丢失 / 重复写入 / App 内 H5 违规" bug this skill has seen. Treat them as non-negotiable. Do NOT try to be clever and shortcut them.

1. **`projectId` first, verified by grep.** Create `.env` with `DEV_PROJECT_ID` → `loadEnv` + `define __APP_PROJECT_ID__` in `vite.config.ts` → **`pnpm build && grep -c "$DEV_PROJECT_ID" dist/assets/*.js` must return ≥ 1**. A green build with empty `projectId` is NOT done. (pitfall #1)
2. **`frontNotifyUrl` carries NO `#` and NO `gatewayOrderNo`.** Just `origin + pathname + '?from=pay-result'`. Do not "helpfully" append the order number to the URL — see the FAQ in pitfall #2 for why that is a trap. (pitfall #2)
3. **Guard rewrites the return URL in MODULE SCOPE, before the router is constructed** — never in `useEffect`/`onMounted`. (pitfall #2)
4. **Recover `gatewayOrderNo` as an OBJECT from storage, not as a field.** The single most common self-inflicted bug is `let s = obj?.gatewayOrderNo ?? fallback()` — the `??` collapses `s` to a string and `s?.gatewayOrderNo` is then forever `undefined` → blank result page. Copy the ❌/✅ block in pitfall #3 verbatim. (pitfall #3)
5. **Two-path checkout (MANDATORY).** Dev iframe preview → `window.open(cashierUrl)` + poll `/status` on the **unchanged pay page**, show result in place, `clearPendingOrder` on PAID. Standalone/prod → `window.location.href = cashierUrl` + return via `frontNotifyUrl`. **Never `location.href` to cashier inside an iframe.** (pitfall #6)
6. **Shape A self-check is a HARD GATE (standalone path).** Simulate `?from=pay-result` with order pre-stashed — see Step 10 Path B. **Iframe path self-check:** click Pay in dev preview → popup opens → pay page polls `/status` and shows result without iframe navigation. (Step 10)
7. **App vs H5 routing (MANDATORY).** Detect App WebView (`X-Client-Type` or UA) → call **`/app/create-order` + `/app/pay`** only. Mobile/system browser → **`/h5/*`** or cashier only. **All pay API calls must use `payFetch`** so `X-Client-Type` matches backend guards (9383/9384). (pitfall #7)

> If you skip any one of these, expect a blank page, a lost/dupe record, or a channel violation in production. The pitfalls below explain the *why*; this block is the *what to do*. Do not proceed to writing UI until you can tick all seven.

---

## Frontend pitfalls — READ FIRST

These are the five integration traps that cause real production bugs. **Read this section before writing any payment code.** Each one was hit and fixed in a real project; skipping them produces silent failures (callback page never opens, duplicate records, blank page on `/prod`).

### 1. `projectId` must be injected at **build time** — and you MUST create a `.env` file first

> ⚠️ **This is the #1 most common failure.** If you skip the `.env` file creation below, `projectId` will be an empty string in the browser, and the payment API will reject every request with `"projectId must not be blank"`.

There are **two layers** to understand:

**Layer 1 — Browser cannot read env vars.** `process.env.DEV_PROJECT_ID` works in Node/agent scripts, **not in frontend JS running in the user's browser.** Vite only statically replaces *specific* `import.meta.env.VITE_*` / defined vars at build time; an arbitrary `process.env.DEV_PROJECT_ID` resolves to `undefined` (empty string) in the browser → empty `projectId` → API rejects the order.

This differs from skills that run as **agent-side scripts** (e.g. `datatable-for-page-api`, which reads `os.environ.get('DEV_PROJECT_ID')` in a Python script and works fine). For frontend browser code you must inject it at build time.

**Layer 2 — Platform Vite process does NOT inherit the agent's env vars.** The agent shell has `DEV_PROJECT_ID` set. But the platform starts the Vite dev server / build as a **separate child process** that does **not** inherit this variable. So `process.env.DEV_PROJECT_ID` is `undefined` inside the Vite process. The only reliable data source is a **`.env` file** in the project root, which Vite's `loadEnv()` reads directly from disk.

#### Step-by-step (MUST follow all 3 steps):

**Step 1 — Create `.env` file (MANDATORY, do this FIRST):**

The agent MUST create a `.env` file in the project root, writing the value from the agent's own `$DEV_PROJECT_ID`:

```bash
# Read from agent shell env and write to project .env
echo "DEV_PROJECT_ID=$DEV_PROJECT_ID" > .env
```

> Without this file, `loadEnv()` finds nothing and `process.env` in the Vite process is empty — the `||` fallback chain below collapses to `''`. **This is not optional.** If `.env` is missing, payment will silently fail.

**Step 2 — Configure `vite.config.ts` to inject via `define`:**

```ts
// vite.config.ts
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  // loadEnv reads from .env / .env.{mode} files in cwd.
  // NOTE: loadEnv does NOT read process.env — the process.env fallback below
  // is a secondary safety net, but the .env file (Step 1) is the primary source.
  const env = loadEnv(mode, process.cwd(), '');
  const projectId =
    env.DEV_PROJECT_ID ||
    process.env.DEV_PROJECT_ID || // secondary fallback — usually empty in platform Vite process
    ''; // do NOT hardcode here — keep the literal in the project, not the skill

  return {
    base: './', // see pitfall #4
    define: {
      __APP_PROJECT_ID__: JSON.stringify(projectId),
    },
    // ...plugins, etc.
  };
});
```

**Step 3 — Verify projectId was actually injected (MANDATORY gate):**

After configuring, the agent MUST verify the value made it into the build output. **This is a hard success criterion — if verification fails, the integration is NOT complete.**

```bash
# Build the project
pnpm build   # or the project's build command

# Verify projectId is embedded in the output (must return >= 1)
grep -c "$DEV_PROJECT_ID" dist/assets/*.js

# If it returns 0, projectId injection FAILED — go back and check:
#   1. Does .env exist and contain DEV_PROJECT_ID=<non-empty>?  (cat .env)
#   2. Does vite.config.ts call loadEnv and define __APP_PROJECT_ID__?
#   3. Re-run build and re-verify.
```

> ⚠️ **Do NOT skip Step 3.** A build that compiles successfully can still have an empty `projectId` (the `define` happily replaces with `""`). The `grep` check is the only way to catch this before runtime.

Then in frontend code, read the constant:

```ts
// pay.ts
declare const __APP_PROJECT_ID__: string;
export const PROJECT_ID = typeof __APP_PROJECT_ID__ !== 'undefined' ? __APP_PROJECT_ID__ : '';
// send `projectId: PROJECT_ID` in cashier / create-order request bodies (NOT in /status)
```

> **Do NOT** write `projectId: process.env.DEV_PROJECT_ID` in frontend fetch bodies. **Do NOT** use `import.meta.env.DEV_PROJECT_ID` unless you have explicitly prefixed your env var as `VITE_DEV_PROJECT_ID` and exposed it. The `define` + global constant pattern above is the reliable one.

### 2. `frontNotifyUrl` MUST NOT contain a `#` hash fragment — and the return guard MUST run BEFORE the router reads the hash

> ⚠️ **Neither the cashier nor the H5 backend relay appends payment params to your final URL.**
>
> - **Cashier mode:** verified against the hosted cashier source — `redirectToMerchantAfterPaid()` does `window.location.href = ctx.bizRedirectUrl` using your `frontNotifyUrl *verbatim*`. It does **NOT** append `orderNo`, `gatewayOrderNo`, `status`, or any other payment param.
> - **H5 mode:** WeChat/Alipay redirect to `/api/pay/general/h5/front-notify?returnUrl=...` first; that endpoint validates `returnUrl` and **302s to your `frontNotifyUrl` as-is** — it does **NOT** append payment params either. Any channel query params on the relay request are discarded.
>
> **Earlier versions of this skill assumed the channel appends payment params on return — that assumption is WRONG and is the root cause of "lost order / blank page" bugs.** The only thing the browser sees on the result page is *exactly* the `frontNotifyUrl` you set (e.g. `https://host/page/{id}/?from=pay-result`). Plan your recovery strategy around that fact: **the order handle must come from YOUR storage, not from the return URL.**

Most SPA projects on this platform use **hash routing** (`/#/some-page`). You still MUST NOT put a `#` in `frontNotifyUrl`: `createHashRouter` reads `window.location.hash` at construction time, and a return URL that carries no hash (which it never does — you build `frontNotifyUrl` without one) makes the router force in a `#/` and boot on the wrong route → **blank page**. The job of the return guard below is to rewrite that no-hash URL into `#/pay-result` before the router reads it.

**Rule:** `frontNotifyUrl` should be a plain URL **without a hash**, carrying only a query *flag* (e.g. `?from=pay-result`). Then a top-level return guard rewrites the URL into the hash route.

```js
// On the pay/checkout page — build the return URL WITHOUT a hash
const frontNotifyUrl =
  window.location.origin + window.location.pathname + '?from=pay-result';
```

> ⚠️ **CRITICAL — timing: the guard MUST run BEFORE the hash router is created, not in a `useEffect`/`onMounted`.**
>
> This is the single most common cause of the **blank return page**. The guard's job is to fix `window.location.hash` so the router boots on the right route. But:
> - A hash router (`createHashRouter` / `createRouter({ history: createWebHashHistory() })`) reads and **initializes** `window.location.hash` at the moment it is **constructed**. If the URL has no hash yet, the router *forces* one in (usually `#/`) and boots from there.
> - `useEffect` (React) and `onMounted` (Vue) run **after** the component mounts — by which point the router module has already been imported and constructed. The router has already read the empty/wrong hash. Rewriting the URL afterwards is **too late**: `history.replaceState` does **not** fire a `hashchange`, so the router never re-parses → it stays on the wrong route → **blank page**.
>
> Therefore the rewrite must happen **synchronously, in module scope, before the router is constructed** (an IIFE that runs at module-eval time, before the `createHashRouter(...)` call on the next lines). Do NOT put it in a hook/effect. Do NOT import a pre-built `router` object and then try to fix the URL in `App`'s body — the import already constructed it.

```ts
// App.tsx (React, hash router) — rewrite in MODULE SCOPE, before createHashRouter.
import { createHashRouter, RouterProvider } from 'react-router-dom';
import { ROUTES } from './router'; // plain ROUTES array, NOT a pre-built router

const PAY_FLAG = 'from';
const PAY_FLAG_VALUE = 'pay-result';
const PAY_PARAMS = ['orderNo', 'gatewayOrderNo', 'status', 'payChannel', 'paidAt'];

// Does a param set look like a payment return? Carries a pay param OR the flag.
// Do NOT depend solely on the `from=pay-result` flag — a channel/proxy may strip
// or alter query params. Either signal, in search OR hash query, counts.
function isPayReturn(sp: URLSearchParams): boolean {
  if (sp.get(PAY_FLAG) === PAY_FLAG_VALUE) return true;
  return PAY_PARAMS.some((k) => !!sp.get(k));
}

// Runs synchronously at module load — BEFORE createHashRouter reads the hash.
(function normalizePayReturnUrl() {
  const url = new URL(window.location.href);
  const hashSp = new URLSearchParams(url.hash.split('?')[1] ?? '');

  // Find which location carries the payment-return payload (search or hash query).
  const source =
    isPayReturn(url.searchParams) ? url.searchParams
    : isPayReturn(hashSp) ? hashSp
    : null;
  if (!source) return; // not a payment return — nothing to do

  // Carry any payment params over (defensive — cashier/H5 relay append NONE today,
  // but a future channel might; keep them in the hash query if present).
  const pairs: string[] = [];
  for (const key of PAY_PARAMS) {
    const v = source.get(key);
    if (v) pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(v)}`);
  }
  source.forEach((value, key) => {
    if (key === PAY_FLAG || PAY_PARAMS.includes(key)) return;
    pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  });

  url.search = '';
  url.hash = `#/pay-result${pairs.length ? `?${pairs.join('&')}` : ''}`;
  // replaceState: no new history entry, no reload — router now sees the clean URL.
  window.history.replaceState(null, '', url.toString());
})();

// Constructed AFTER the rewrite above — so it reads the corrected hash.
const router = createHashRouter(ROUTES);

export default function App() {
  return <RouterProvider router={router} />;
}
```

> Why `replaceState` and not `window.location.replace(...)`? `replace()` triggers a full navigation/reload that races with module init and re-runs the IIFE. `replaceState` mutates the URL in place with no reload and no `hashchange` — exactly what we want, because the router (constructed on the very next line) then reads the already-correct hash.

```ts
// Vue 3 equivalent — in main.ts BEFORE createApp/use(router), NOT in App.vue onMounted.
import { createApp } from 'vue';
import { createRouter, createWebHashHistory } from 'vue-router';
import App from './App.vue';

const PAY_FLAG = 'from';
const PAY_FLAG_VALUE = 'pay-result';
const PAY_PARAMS = ['orderNo', 'gatewayOrderNo', 'status', 'payChannel', 'paidAt'];
function isPayReturn(sp) {
  if (sp.get(PAY_FLAG) === PAY_FLAG_VALUE) return true;
  return PAY_PARAMS.some((k) => !!sp.get(k));
}

;(function normalizePayReturnUrl() {
  const url = new URL(window.location.href)
  const hashSp = new URLSearchParams(url.hash.split('?')[1] ?? '')
  const source = isPayReturn(url.searchParams) ? url.searchParams
    : isPayReturn(hashSp) ? hashSp : null
  if (!source) return
  const pairs = []
  for (const key of PAY_PARAMS) {
    const v = source.get(key)
    if (v) pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(v)}`)
  }
  source.forEach((value, key) => {
    if (key === PAY_FLAG || PAY_PARAMS.includes(key)) return
    pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
  })
  url.search = ''
  url.hash = `#/pay-result${pairs.length ? `?${pairs.join('&')}` : ''}`
  window.history.replaceState(null, '', url.toString())
})()

const router = createRouter({ history: createWebHashHistory(), routes: [/* ... */] });
createApp(App).use(router).mount('#app');
```

> For **path-routed** SPAs (BrowserRouter / `createWebHistory`), `frontNotifyUrl` can be the path directly (`origin + '/pay-result'`) — no guard needed. Hash routing is the case that needs the flag + pre-router guard.

> ❓ **FAQ — "Since the return URL never has the order number, why don't I just append `gatewayOrderNo` to `frontNotifyUrl` myself so it survives the redirect?"**
>
> You *can* build `frontNotifyUrl = ... + '?from=pay-result&gatewayOrderNo=' + gw`, but **do not — it is unnecessary and harmful.** The reliable recovery source for `gatewayOrderNo` is the blob **you wrote to `localStorage` (`pendingOrder:{gatewayOrderNo}`) before the redirect**, which is far more durable than a URL query (URLs get rewritten/stripped by proxies, channels, private mode; `localStorage` only disappears if the user actively clears cache). Putting the order number in the URL is therefore only a redundant copy of storage, not a replacement, and it adds two real risks:
> 1. **Exposure** — the gateway order number sits in the address bar, viewable/screenshotable/editable by the user.
> 2. **Idempotency-key poisoning** — an attacker can hand-craft `?gatewayOrderNo=<someone else's order>` to try to trip the `paid:{...}` write. (`/status` truth-checks the real payment state, so this is contained, but it is needless attack surface.)
>
> **Conclusion: `frontNotifyUrl` carries ONLY the `?from=pay-result` flag. The order handle comes from storage. This is intentional design — do not "helpfully" also put the order number in the URL.** (The guard's `PAY_PARAMS` scan still picks up a param *if a channel ever echoes one* — that path is defensive-only, not something you build the URL around.)

### 3. Recover `gatewayOrderNo` from storage FIRST — the return URL does NOT echo it back

> ⚠️ **Read this together with pitfall #2.** Because both cashier and H5 relay return to `frontNotifyUrl` *as-is* (no params appended), the result URL normally contains **no `gatewayOrderNo` at all**. So `gatewayOrderNo` MUST be recovered primarily from **storage** you wrote before leaving for payment — the URL-query fallback is only a defensive extra for the day some channel *does* echo a param. **Do not design the recovery around "the URL will have the order number" — it usually will not.**

> 🛠 **Two independent chains — both must work.** Do not assume that because the guard (pitfall #2) landed the URL on `#/pay-result`, the result page is fine. The guard only fixes **routing** (URL → route match). **Recovering the data** (`gatewayOrderNo` → able to poll `/status` and write the record) is a *separate* chain (this pitfall). A paid order can land on the right route but still show "未找到订单 / blank" if this recovery chain is broken. The Shape A self-check (Step 10) verifies BOTH at once — that is why it is mandatory.

> ⚠️ **THE most common self-inflicted bug — keep `stashed` as the OBJECT, read the field only at the very end.** The recovery variable holds the parsed *object* (`{ gatewayOrderNo, amount, ... }`). If you write `let s = obj?.gatewayOrderNo ?? fallback()`, then when sessionStorage hits, the `??` collapses `s` to the order-number **string**, and every subsequent `s?.gatewayOrderNo` is forever `undefined` → recovery fails → **paid order lands on a "未找到订单 / blank" page**. Copy this block verbatim:

```js
// ❌ WRONG — stashed collapses to a STRING (the order number), so the later
//           stashed?.gatewayOrderNo is ALWAYS undefined → blank result page.
let stashed = JSON.parse(sessionStorage.getItem('pendingOrder') || 'null');
stashed = stashed?.gatewayOrderNo ?? recoverPendingFromLocalStorage(); // BUG
const gatewayOrderNo = stashed?.gatewayOrderNo || /* url */ '';        // always '' here

// ✅ RIGHT — keep stashed as the OBJECT; only read the field at the very end.
let stashed = JSON.parse(sessionStorage.getItem('pendingOrder') || 'null');
if (!stashed || typeof stashed !== 'object' || !stashed.gatewayOrderNo) {
  stashed = recoverPendingFromLocalStorage(); // localStorage OBJECT, not a field
}
const gatewayOrderNo =
  stashed?.gatewayOrderNo ||                                // field read ONCE, last
  hq.get('gatewayOrderNo') || q.get('gatewayOrderNo') ||    // URL defensive fallback
  hq.get('orderNo') || q.get('orderNo') || '';
```

There are **three** recovery sources, tried in this order:

1. **`sessionStorage` (primary)** — stash `pendingOrder` (`{ gatewayOrderNo, amount, name, ... }`) on the pay page **before** redirecting to the cashier. Same-origin, survives the redirect within the same tab.

2. **`localStorage` (MANDATORY cross-origin fallback)** — `sessionStorage` is **not reliable across payment redirects**: the cashier is a different origin (`m10096.nuwax.com`), and H5 channel pages are also outside your app. Some browsers / privacy modes / "return in a new tab" scenarios drop or isolate session storage. **You MUST also persist the same pending-order blob to `localStorage` under a key that includes `gatewayOrderNo`** (e.g. `pendingOrder:{gatewayOrderNo}`) on the pay page **before** redirecting to the cashier or invoking H5 pay. The result page scans those keys and recovers the most-recent one when sessionStorage is gone. Without this, a *paid* order returns to a "未找到订单 / failed" screen in exactly the cases that matter most (cross-origin, new tab). Clear the entry after a successful PAID write to avoid unbounded growth.

3. **URL query (defensive fallback only)** — scan `location.search` AND the hash query for `gatewayOrderNo`/`orderNo`. Usually empty (cashier and H5 relay append nothing), but harmless to check. Add `useSearchParams()` from your router as one more source if available.

```js
// On the PAY/checkout page — stash BOTH sessionStorage AND localStorage before redirect.
const pending = { gatewayOrderNo, amountInFen, name, message };
const pendingJson = JSON.stringify(pending);
sessionStorage.setItem('pendingOrder', pendingJson);             // primary
localStorage.setItem(`pendingOrder:${gatewayOrderNo}`, pendingJson); // cross-origin fallback

// On the RESULT page — try every source so a paid order is never lost.
function resolveGatewayOrderNo() {
  // 1. sessionStorage
  const stashed = JSON.parse(sessionStorage.getItem('pendingOrder') || 'null');
  if (stashed?.gatewayOrderNo) return stashed.gatewayOrderNo;
  // 2. localStorage fallback (scan all pendingOrder:* keys, freshest wins)
  const lsHit = recoverPendingFromLocalStorage();
  if (lsHit?.gatewayOrderNo) return lsHit.gatewayOrderNo;
  // 3. URL query — defensive (cashier usually appends nothing; pitfall #2)
  const q = new URLSearchParams(window.location.search);
  const hq = new URLSearchParams((window.location.hash.split('?')[1]) || '');
  return hq.get('gatewayOrderNo') || q.get('gatewayOrderNo')
      || hq.get('orderNo') || q.get('orderNo') || '';
}

function recoverPendingFromLocalStorage() {
  const entries = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key || !key.startsWith('pendingOrder:')) continue;
    try {
      const o = JSON.parse(localStorage.getItem(key) || 'null');
      if (o?.gatewayOrderNo) entries.push(o);
    } catch { /* ignore malformed */ }
  }
  return entries.length ? entries[entries.length - 1] : null; // last written = most recent
}
```

### 4. Sub-path deployment: keep `/api/*` absolute, set Vite `base: './'`

Projects deploy under a sub-path (dev: `/page/{projectId}-xxxx/dev/`, prod: `/page/{projectId}-xxxx/prod/`). Two rules that are easy to get backwards:

- **Static assets (JS/CSS)** → use a **relative** base. Set `base: './'` in `vite.config.ts` so the bundle references `./assets/...`. With the default `base: '/'`, assets resolve to the domain root under a sub-path → **404 → blank page** (the result page's JS won't even load).
- **API calls `/api/pay/general/*`** → MUST stay **absolute** (`/api/...`). The API gateway is mounted at the domain root `/api/`, independent of the project's sub-path. Do **not** "relativize" the API path together with assets.

`frontNotifyUrl` should use `window.location.origin + window.location.pathname` (read at runtime) so it automatically follows whatever sub-path (`/dev/` or `/prod/`) the user is actually on — never hardcode `/dev` or `/prod`.

### 5. Persisting a business record after `PAID` MUST be idempotent (by `gatewayOrderNo`)

The status poll fires `onPaid` once you hit `PAID`, but your **business write** (creating an order row, recording a tip, etc.) can still execute multiple times because:
- React `<StrictMode>` runs `useEffect` **twice** in dev (mount → unmount → mount) — an in-memory `let done = false` flag resets on the second run → **duplicate records**.
- The user refreshes the result page; the flag is gone.

**Always gate the business write with a *persisted* idempotency key keyed by `gatewayOrderNo`** (localStorage/sessionStorage), not an in-memory variable. Reserve the slot before writing, roll it back if the write fails.

**Also: never silently SKIP the write when `sessionStorage` is missing — fall back to the pay status.** The `onPaid` callback receives `info` from `/status`, which contains `orderAmount` (and `paidAt`, `payChannel`). If the stashed `pendingOrder` is gone (new tab, refresh after close, private mode), the order is still *paid* and `gatewayOrderNo` was recovered from the URL (pitfall #3) — so you still have enough to write the record. Recover `amount` from `info.orderAmount` and use a sensible default (e.g. an "anonymous" name) for any business field you can't recover. The bug to avoid: wrapping the write in `if (stashed) {...}` so a missing stash turns a *successful payment into a lost record*.

```js
const KEY = `paid:${gatewayOrderNo}`;
async function onPaid(info) {
  if (localStorage.getItem(KEY) === '1') { setState('paid'); return; }
  localStorage.setItem(KEY, '1');          // reserve first
  try {
    // stashed may be missing — fall back to the status payload so a paid order
    // is NEVER turned into a lost record. Never gate the whole write on stashed.
    const stashed = JSON.parse(sessionStorage.getItem('pendingOrder') || 'null');
    await createBusinessRecord({
      gatewayOrderNo,
      amount: stashed?.amount ?? info.orderAmount ?? 0, // status payload fallback
      name: stashed?.name ?? 'anonymous',
      message: stashed?.message ?? '',
      // ...any other fields, each with a fallback
    });
    sessionStorage.removeItem('pendingOrder');
    localStorage.removeItem(`pendingOrder:${gatewayOrderNo}`); // pitfall #3 fallback cleanup
  } catch (e) {
    localStorage.removeItem(KEY);           // roll back so it can retry
    console.error(e);
    // surface it — a silent failure here means a paid order with no record
    setState('paid'); setStatus('支付成功，但记录同步失败，请联系商家');
    return;
  }
  setState('paid');
}
```

> ⚠️ **Persist `gatewayOrderNo` into your BUSINESS DATA TABLE — not just browser storage.** Browser storage (sessionStorage/localStorage) is only the *recovery* mechanism for the post-return page; it is NOT a record of the payment. The user clears their cache, switches devices, or a support agent looks up an order → that data is gone. **The business record you write on `PAID` MUST include `gatewayOrderNo` as a dedicated column in your data table** (e.g. a `donations`/`orders` table with a `gateway_order_no` column). This is the single source of truth for traceability — later you can resync any order by passing its stored `gatewayOrderNo` back into `/status`.
>
> **Write is half the job — expose the read side too.** A column you INSERT but never SELECT is invisible: you can't show it, can't search by it, can't reconcile against it. When you design the data table and its SQL APIs (via the `datatable-for-page-api` skill), make sure EVERY read API (list/get/search) `SELECT`s `gateway_order_no` alongside the business fields, and the frontend model carries `gatewayOrderNo` end-to-end. The common bug: `add` writes the column, but `getAll`/`search` forget to SELECT it → the gateway order number is stored but unreachable for display or dispute resolution.
>
> Why bother if you don't render it in the UI? Because the moment a customer says "I paid but it's not in the wall", `gatewayOrderNo` is the only handle that lets you look the payment up in `/status`, prove it paid, and decide whether to manually repair the record. No `gatewayOrderNo` in the table = no way to investigate. Always persist it, and always be able to read it back.

### 6. Dev iframe preview: `window.open` + in-place poll (never `location.href`)

> ⚠️ **Mandatory for dev preview.** Platform dev embeds your project in an **iframe**. If you `window.location.href = cashierUrl` inside the iframe, the iframe navigates cross-origin to the cashier and back — causing blank pages, lost storage, and broken layouts. **The only supported dev-iframe pattern is:**

```
系统预览壳（外层，不变）
└── iframe（项目页，始终不离开）
      点支付 → stashPendingOrder → window.open(收银台)  ← 新窗口
      原页轮询 POST /api/pay/general/status
      PAID → 原页展示结果 + clearPendingOrder
```

**Standalone / published prod** (`window.self === window.top`):

```
项目页 → stashPendingOrder → location.href = 收银台
收银台付完 → frontNotifyUrl 回跳 → 结果页轮询 /status
```

**Rules (non-negotiable):**

| Step | iframe dev preview | standalone / prod |
|------|-------------------|-------------------|
| Before pay | `stashPendingOrder(...)` | `stashPendingOrder(...)` |
| Open cashier | `window.open(cashierUrl, '_blank', 'noopener,noreferrer')` | `window.location.href = cashierUrl` |
| Wait for result | Poll `/status` on **pay page** (same component or inline UI) | Cashier returns to `frontNotifyUrl` → result page polls |
| On PAID | Show success **in place** + `clearPendingOrder(gatewayOrderNo)` + idempotent business write | Result page flow (pitfall #2–#5) + cleanup stash |
| `frontNotifyUrl` | Still pass to `/cashier` API (popup redirects there after pay — iframe ignores it) | Drives return redirect (no `#`, pitfall #2) |

**Copy-paste helpers:**

```js
export function isEmbeddedPreview() {
  try { return window.self !== window.top; } catch { return true; }
}

export function buildFrontNotifyUrl() {
  return window.location.origin + window.location.pathname + '?from=pay-result';
}

const PENDING_LATEST_KEY = 'pendingOrder:latest';

export function stashPendingOrder(pending) {
  const json = JSON.stringify(pending);
  const gw = pending.gatewayOrderNo;
  localStorage.setItem(`pendingOrder:${gw}`, json);
  localStorage.setItem(PENDING_LATEST_KEY, json);
  try { sessionStorage.setItem('pendingOrder', json); } catch { /* ignore */ }
}

export function clearPendingOrder(gatewayOrderNo) {
  try { sessionStorage.removeItem('pendingOrder'); } catch { /* ignore */ }
  localStorage.removeItem(`pendingOrder:${gatewayOrderNo}`);
  localStorage.removeItem(PENDING_LATEST_KEY);
}

/** iframe: window.open; standalone: same-window redirect */
export function openCashier(cashierUrl) {
  if (isEmbeddedPreview()) {
    const w = window.open(cashierUrl, '_blank', 'noopener,noreferrer');
    if (!w) throw new Error('弹窗被拦截，请允许弹出窗口后重试');
    return w;
  }
  window.location.href = cashierUrl;
  return null;
}

/**
 * iframe path: after openCashier, call this on the pay page.
 * Shows result via callbacks — no URL return needed.
 */
export function pollUntilPaid(gatewayOrderNo, { onPaid, onFailed, intervalMs = 2500, timeoutMs = 60 * 60 * 1000 } = {}) {
  let stop = false;
  const start = Date.now();
  (async () => {
    while (!stop && Date.now() - start < timeoutMs) {
      try {
        const info = await queryStatus(gatewayOrderNo);
        if (info.status === 'PAID') { onPaid?.(info); return; }
        if (info.status === 'FAILED' || info.status === 'CLOSED') { onFailed?.(info); return; }
      } catch { /* keep polling */ }
      await new Promise((r) => setTimeout(r, intervalMs));
    }
    if (!stop) onFailed?.({ status: 'CLOSED' });
  })();
  return () => { stop = true; };
}
```

**iframe pay flow (cashier — copy verbatim):**

```js
async function payWithCashierInIframe({ amountInFen, subject, name, message, onPaid, onFailed }) {
  const frontNotifyUrl = buildFrontNotifyUrl(); // for popup return only
  const { gatewayOrderNo, cashierUrl } = await createCashierOrder({ amountInFen, subject, frontNotifyUrl });
  const pending = { gatewayOrderNo, amountInFen, subject, name, message };
  stashPendingOrder(pending);
  openCashier(cashierUrl); // window.open — iframe stays put
  return pollUntilPaid(gatewayOrderNo, {
    onPaid: async (info) => {
      await writeBusinessRecordOnce(pending, info); // pitfall #5
      clearPendingOrder(gatewayOrderNo);
      onPaid?.(info);
    },
    onFailed,
  });
}
```

**standalone pay flow (cashier):**

```js
async function payWithCashierStandalone({ amountInFen, subject, name, message }) {
  const frontNotifyUrl = buildFrontNotifyUrl();
  const { gatewayOrderNo, cashierUrl } = await createCashierOrder({ amountInFen, subject, frontNotifyUrl });
  stashPendingOrder({ gatewayOrderNo, amountInFen, subject, name, message });
  window.location.href = cashierUrl; // leaves page — result handled on return URL
}
```

**Unified entry (use this in PayButton):**

```js
export async function payWithCashier(opts) {
  if (isEmbeddedPreview()) return payWithCashierInIframe(opts);
  return payWithCashierStandalone(opts);
}
```

> ❌ **Never do this in iframe dev preview:** `window.location.href = cashierUrl` — this is the #1 cause of post-pay blank iframe.
>
> **Popup blocked?** Show a clear error asking the user to allow popups. Do not fall back to `location.href` inside iframe.

### 7. App WebView vs mobile browser — route to the correct API (never mix H5 and App)

> ⚠️ **Channel policy:** WeChat/Alipay **H5 pay inside an App WebView is a violation** — the backend rejects it with error `9383` (`pay_h5_not_allowed_in_app`). Conversely, **App native pay from a system browser** is rejected with `9384` (`pay_app_native_requires_app`). Your frontend MUST pick the API set before calling create-order.
>
> ⚠️ **Backend reads the HTTP header `X-Client-Type`, not a JS global alone.** App 壳注入 `window.__NUWAX_CLIENT_TYPE__` 后，**每次支付 API 请求都必须带上同名 Header**（用下方 `payFetch`）。仅改页面内变量、Header 未带 → 前端走 `/app/*` 后端判非 App（9384），或前端走 `/h5/*` 后端判 App（9383）。

**Detection + fetch wrapper (copy into `pay.ts`):**

```ts
export const PAY_HEADER_CLIENT_TYPE = 'X-Client-Type';

function isAppFromClientTypeValue(clientType: string): boolean {
  const n = clientType.trim().toLowerCase();
  if (['web', 'h5', 'browser', 'wap'].includes(n)) return false;
  if (n.startsWith('app') || ['native', 'ios', 'android', 'mobile', 'mobile-app', 'nuwax-app'].includes(n)) return true;
  return true; // 非 web 类取值 → App 壳（与后端 PayAppWebViewDetector 一致）
}

function isAppFromUserAgent(): boolean {
  const ua = navigator.userAgent || '';
  if (/NuwaxApp|NUWAX_APP|nuwax-app/i.test(ua)) return true;
  if (/nuwax/i.test(ua) && (/webview|;\s*wv\)/i.test(ua))) return true;
  return false;
}

/** App 壳注入：ios | android | app 等；纯浏览器返回 undefined */
export function resolveClientTypeHeader(): string | undefined {
  const fromBridge = (window as any).__NUWAX_CLIENT_TYPE__ as string | undefined;
  if (fromBridge?.trim()) return fromBridge.trim();
  if (isAppFromUserAgent()) return 'app';
  return undefined;
}

/** 前端路由：与后端 9383/9384 守卫对齐 */
export function isAppWebView(): boolean {
  const ct = resolveClientTypeHeader();
  if (ct) return isAppFromClientTypeValue(ct);
  return isAppFromUserAgent();
}

/** 所有支付 API 必须经此发起 — 自动附带 X-Client-Type */
export function payFetch(input: RequestInfo, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const clientType = resolveClientTypeHeader();
  if (clientType) headers.set(PAY_HEADER_CLIENT_TYPE, clientType);
  return fetch(input, { ...init, headers });
}

/** Pick pay path before create-order */
export function resolvePayApiSet(): 'app' | 'h5' | 'cashier' {
  if (isAppWebView()) return 'app';
  return 'h5'; // or 'cashier' if you don't need custom UI
}
```

| Client | APIs | After pay |
|--------|------|-----------|
| **App WebView** | `POST /api/pay/general/app/create-order` → `POST /api/pay/general/app/pay` | **WxPay（当前渠道）：** `redirectUrl`（`weixin://...`）→ `wx.miniapp.launchMiniProgram`；**AliPay：** 打开 `redirectUrl`；poll `/status` |
| **Mobile/system browser** (custom UI) | `POST /api/pay/general/h5/create-order` → `POST /api/pay/general/h5/pay` | Same two-path rule as pitfall #6 (iframe popup vs standalone return) |
| **Any** (default) | `POST /api/pay/general/cashier` | Cashier two-path rule (pitfall #6) |

**App native invoke (WebView only — align with `App 原生支付.md`):**

> ⚠️ **当前微信 App 渠道（安心付）不走 `wxPayParams`。** 网关返回 `invokeType=REDIRECT_URL` + `redirectUrl`（`weixin://dl/business/...`），前端须解析 `query=` 后的 Base64，用 **`wx.miniapp.launchMiniProgram`** 拉起小程序（`userName=gh_cd6acad9a40d`，`path=ipay/main?{param}`）。**不要**对微信 `redirectUrl` 使用 `uni.requestPayment` / 直接打开链接。

```js
/** 从网关 redirectUrl 解析 Param（query= 后的 Base64 段） */
function parseAnxinfuMiniProgramParam(redirectUrl) {
  if (!redirectUrl) return '';
  const q = redirectUrl.split('query=')[1]?.split('&')[0] ?? '';
  return q; // 已是 Base64，勿 encodeURIComponent
}

function launchAnxinfuWxPay(redirectUrl) {
  const param = parseAnxinfuMiniProgramParam(redirectUrl);
  if (!param) return Promise.reject(new Error('无法从 redirectUrl 解析支付参数'));
  return new Promise((resolve, reject) => {
    wx.miniapp.launchMiniProgram({
      userName: 'gh_cd6acad9a40d',
      path: 'ipay/main?' + param,
      miniprogramType: 0,
      success: (res) => {
        const status = res?.data?.status;
        if (status === 'success') resolve(res);
        else if (status === 'cancel') reject(Object.assign(new Error('用户取消支付'), { status }));
        else resolve(res);
      },
      fail: reject,
    });
  });
}

/** 统一调起 — Bill 与通用项目 /app/pay 响应结构一致 */
async function invokeAppPay(pay) {
  if (pay.payChannel === 'WxPay') {
    if (pay.wxPayParams) {
      await invokeWxPrepay(pay.wxPayParams); // 其他渠道兼容
      return;
    }
    if (pay.redirectUrl?.startsWith('weixin://')) {
      await launchAnxinfuWxPay(pay.redirectUrl);
      return;
    }
    throw new Error('微信 App 支付缺少 redirectUrl');
  }
  if (pay.payChannel === 'AliPay') {
    if (pay.invokeType === 'REDIRECT_URL' && pay.redirectUrl) {
      await openPayUrl(pay.redirectUrl); // https://qr.alipay.com/... 或 alipays://
      return;
    }
    if (pay.invokeType === 'QRCODE_FALLBACK' && pay.qrCodeContent) {
      showQrCode(pay.qrCodeContent);
      return;
    }
  }
  throw new Error('无法识别的支付调起参数');
}

async function payWithAppNative({ amountInFen, subject, payChannel, onPolling, onPaid, onFailed }) {
  if (!isAppWebView()) throw new Error('App native pay requires App WebView');
  const { orderNo, gatewayOrderNo } = await createAppOrder({ amountInFen, subject });
  stashPendingOrder({ gatewayOrderNo, amountInFen, subject });

  const res = await payFetch('/api/pay/general/app/pay', {
    method: 'POST',
    body: JSON.stringify({ orderNo, payChannel }),
  });
  const json = await res.json();
  if (json.code !== '0000') throw new Error(json.message);
  const pay = { ...json.data, payChannel: json.data.payChannel ?? payChannel };

  await invokeAppPay(pay); // launchMiniProgram / openPayUrl — 勿把 redirectUrl 当支付宝专用

  onPolling?.();
  return pollUntilPaid(gatewayOrderNo, {
    onPaid: async (info) => { await onPaid?.(info); clearPendingOrder(gatewayOrderNo); },
    onFailed,
  });
}
```

> **`launchMiniProgram` 的 `success` 仅 UX** — 业务是否支付成功必须轮询 `POST /api/pay/general/status` 直到 `status === 'PAID'`。
>
> **Bill 订单（需登录）：** App → `POST /api/bill/order/pay/app-native` + `GET /api/bill/order/settlement-status`；手机浏览器 → `POST /api/bill/order/pay/h5-web`。调起逻辑同上 `invokeAppPay`。

---

## Important: Sandbox vs Production paths

There are **two different contexts** where these APIs are called, and the paths differ:

### 1. Agent development (debugging from the skill CLI / curl)

When **you (the agent)** test the API during development, use the sandbox path + sandbox key:

```
POST $PLATFORM_BASE_URL/api/v1/4sandbox/pay/general/cashier
Authorization: Bearer $SANDBOX_ACCESS_KEY
```

Environment variables `PLATFORM_BASE_URL` and `SANDBOX_ACCESS_KEY` are pre-set in the sandbox.

### 2. Project runtime (frontend code in the published project)

When **the published project's frontend code** calls the API at runtime, it MUST use the **same-origin** path with **no Authorization header** — the browser sends the login cookie/session automatically:

```
POST /api/pay/general/cashier
```

**Never** put a Bearer token in frontend code. The login session identifies the tenant.

> Quick decision: **Are you writing code that runs in the agent shell (curl/python)?** → sandbox path + Bearer key. **Are you writing frontend JS that runs in the user's browser?** → same-origin `/api/...`, no key, `projectId` injected at build time (see pitfall #1).

---

## API Reference

All endpoints are `POST` with `Content-Type: application/json`. Every response is wrapped in:

```json
{ "code": "0000", "message": "success", "data": { ... } }
```

`code === "0000"` means success. On failure, `message` contains the error.

### 1. Cashier mode — `POST /api/pay/general/cashier`

Creates a scan order and a cashier session, returns the cashier URL.

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `projectId` | string | yes | Project ID — from `DEV_PROJECT_ID`, injected at build time (see [pitfall #1](#1-projectid-must-be-injected-at-build-time-not-read-at-runtime)). Namespaces order numbers to prevent collisions between projects. |
| `bizOrderNo` | string | no | Your own order number. If provided, the final order number is `GP1_{projectId}_{bizOrderNo}` (idempotent — safe for retries). If omitted, the system auto-generates `GP2_{projectId}_{timestamp}_{random}`. |
| `orderAmount` | number | yes | Order amount in **fen** (分). `100` = 1.00 RMB. |
| `subject` | string | yes | Order title / product description (shown on the cashier). |
| `frontNotifyUrl` | string | no | Page URL to redirect the browser back to after payment. **No `#` hash** — see [pitfall #2](#2-frontnotifyurl-must-not-contain-a--hash-fragment). ⚠️ The cashier returns this URL **AS-IS and appends NO payment params** — so do not rely on the return URL carrying `gatewayOrderNo`; recover the order handle from storage (pitfall #3). Can be empty. |

**Response `data`**

| Field | Type | Description |
|-------|------|-------------|
| `orderNo` | string | Business order number (`GP1_` or `GP2_` prefix). |
| `gatewayOrderNo` | string | Gateway payment order number — keep this, you need it for `/status` and as the idempotency key. |
| `cashierUrl` | string | Cashier URL — **iframe dev:** `window.open(cashierUrl)`; **standalone/prod:** `window.location.href = cashierUrl` (pitfall #6). |

### 2. H5 create order — `POST /api/pay/general/h5/create-order`

Step 1 of H5 mode: creates the gateway order (does not call the channel yet). **Mobile/system browser only** — App WebView receives `9383`.

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `projectId` | string | yes | Project ID — from `DEV_PROJECT_ID`, injected at build time (see [pitfall #1](#1-projectid-must-be-injected-at-build-time-not-read-at-runtime)). |
| `bizOrderNo` | string | no | Your own order number. If provided → `GP1_{projectId}_{bizOrderNo}`; if omitted → auto-generated `GP2_{projectId}_{timestamp}_{random}`. |
| `orderAmount` | number | yes | Order amount in **fen** (分). |
| `subject` | string | yes | Order title. |

**Response `data`**

| Field | Type | Description |
|-------|------|-------------|
| `orderNo` | string | Business order number (`GP1_` or `GP2_` prefix). |
| `gatewayOrderNo` | string | Gateway payment order number. |

### 3. H5 pay — `POST /api/pay/general/h5/pay`

Step 2 of H5 mode: invokes the payment channel on an existing H5 order. The client IP is taken from the request automatically. **Mobile/system browser only** — App WebView receives `9383`.

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `orderNo` | string | yes | The `orderNo` returned by `/h5/create-order`. |
| `payChannel` | string | yes | `"WxPay"` (微信支付) or `"AliPay"` (支付宝). Case-insensitive. |
| `frontNotifyUrl` | string | yes | Page URL to return to after payment. Must be a valid `http(s)://...` URL with a host. **No `#` hash** — see [pitfall #2](#2-frontnotifyurl-must-not-contain-a--hash-fragment). The backend wraps this into a channel callback relay (`/api/pay/general/h5/front-notify`) before calling WeChat/Alipay — you still pass your final target page here; do not call the relay URL yourself. |

**Response `data`**

| Field | Type | Description |
|-------|------|-------------|
| `orderNo` | string | Business order number. |
| `gatewayOrderNo` | string | Gateway payment order number. |
| `formHtml` | string | Auto-submit form HTML — when `invokeType === "FORM_HTML"`, write it into the page (`document.body.insertAdjacentHTML('beforeend', formHtml)`). |
| `redirectUrl` | string | Jump URL — when `invokeType === "REDIRECT_URL"`, redirect the browser. |
| `invokeType` | string | `"FORM_HTML"` / `"REDIRECT_URL"` / `"QRCODE_FALLBACK"`. |
| `status` | string | Order status right after the call: `"INIT"` / `"PENDING"` / `"PAID"` / `"FAILED"` / `"CLOSED"`. |

**H5 channel return relay (internal — do NOT call from frontend)**

When `/h5/pay` is called, the backend replaces your `frontNotifyUrl` with a channel callback URL of the form:

```text
{origin}/api/pay/general/h5/front-notify?returnUrl={urlencoded frontNotifyUrl}
```

After the user pays, WeChat/Alipay hit that relay (`GET` or `POST`, no login required). The relay validates `returnUrl` and responds with **302** to your original `frontNotifyUrl`. You never construct or fetch this URL yourself — just pass the final result-page URL as `frontNotifyUrl` in `/h5/pay`.

> ⚠️ **App WebView:** do **not** call `/h5/create-order` or `/h5/pay` — use App native endpoints below (pitfall #7).

### 4. App create order — `POST /api/pay/general/app/create-order`

Step 1 of **App native mode** (App WebView only). Creates the gateway order without calling the channel.

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `projectId` | string | yes | Project ID — from `DEV_PROJECT_ID`, injected at build time. |
| `bizOrderNo` | string | no | Same semantics as H5 — `GP1_` / `GP2_` prefixes. |
| `orderAmount` | number | yes | Amount in **fen** (分). |
| `subject` | string | yes | Order title. |

**Response `data`**

| Field | Type | Description |
|-------|------|-------------|
| `orderNo` | string | Business order number. |
| `gatewayOrderNo` | string | Gateway payment order number — use for `/status` and stash. |

**Errors:** non-App clients receive `9384` (`pay_app_native_requires_app`).

### 5. App pay — `POST /api/pay/general/app/pay`

Step 2 of **App native mode**: invokes channel and returns invoke params. No `frontNotifyUrl` — poll `/status` after SDK / mini-program callback.

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `orderNo` | string | yes | `orderNo` from `/app/create-order`. |
| `payChannel` | string | yes | `"WxPay"` or `"AliPay"`. |

**Response `data`**

| Field | Type | Description |
|-------|------|-------------|
| `orderNo` | string | Business order number. |
| `gatewayOrderNo` | string | Gateway payment order number. |
| `payChannel` | string | `"WxPay"` / `"AliPay"`. |
| `invokeType` | string | `"REDIRECT_URL"` (current WxPay & most AliPay) or `"QRCODE_FALLBACK"` (AliPay fallback). |
| `redirectUrl` | string | **WxPay（当前渠道）：** `weixin://dl/business/?appid=...&path=ipay/main&query={Base64}&env_version=release` → 解析 `query` 后 `wx.miniapp.launchMiniProgram`，**不要**直接打开或 `uni.requestPayment`。**AliPay：** `https://qr.alipay.com/...` 或 `alipays://...` → 打开链接。 |
| `wxPayParams` | object | 其他微信渠道 prepay JSON（**当前安心付渠道为 null**）。有值时用 `invokeWxPrepay` / `PayReq` 兼容。 |
| `qrCodeContent` | string | AliPay `invokeType=QRCODE_FALLBACK` 时的二维码内容。 |
| `alipayTradeNo` | string | 部分支付宝 SDK 使用（当前多为 null）。 |
| `status` | string | 一般为 `"PENDING"`。 |

**WxPay 示例（当前实际返回）：**

```json
{
  "code": "0000",
  "data": {
    "orderNo": "GP1_123_xxx",
    "gatewayOrderNo": "12026063020260474001",
    "payChannel": "WxPay",
    "invokeType": "REDIRECT_URL",
    "redirectUrl": "weixin://dl/business/?appid=wx98a5c8f239de55f8&path=ipay/main&query=OTk5OTQjMTIwMjYwNjMwMjExMDQ1NzgwMDE=&env_version=release",
    "wxPayParams": null,
    "status": "PENDING"
  }
}
```

**AliPay 示例：**

```json
{
  "code": "0000",
  "data": {
    "payChannel": "AliPay",
    "invokeType": "REDIRECT_URL",
    "redirectUrl": "https://qr.alipay.com/bax03117xnkonkdhllom55ed"
  }
}
```

**前端调起：** 使用 pitfall #7 的 `invokeAppPay(pay)` — 按 `payChannel` + `invokeType` 分支，**禁止**把 `redirectUrl` 仅当作支付宝字段。

**Errors:** non-App clients receive `9384`. App WebView calling `/h5/*` receives `9383`.

### 6. Status query — `POST /api/pay/general/status`

Queries the payment status and synchronizes with the channel. Safe to poll. **This is the only reliable way to confirm payment success — poll it on the return page.**

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `gatewayOrderNo` | string | yes | Gateway order number from `/cashier`, `/h5/create-order`, or `/app/create-order`. |

**Response `data`**

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `"INIT"` / `"PENDING"` / `"PAID"` / `"FAILED"` / `"CLOSED"`. |
| `payChannel` | string | `"WxPay"` / `"AliPay"` / `"UnionPay"` (null if not yet paid). |
| `payMode` | string | `"scan"` / `"h5"` / `"app"` / `"minipay"`. |
| `orderAmount` | number | Amount in fen. |
| `paidAt` | string | ISO timestamp when paid (null if not paid). |

Treat the order as paid when `status === "PAID"`. Do **not** rely on `paidAt` alone.

---

## Agent workflow

> ⚠️ **projectId bootstrap is Step 0 — it MUST be completed and verified before writing any payment code.** If Step 0 is not done, every subsequent step will fail at runtime with `"projectId must not be blank"`. See pitfall #1 for the full explanation.

### Standard integration (cashier mode — recommended starting point)

**Step 0 — Bootstrap projectId (MANDATORY, do this FIRST):**

```bash
# 0a. Create .env file with projectId (the Vite process cannot read agent shell env)
echo "DEV_PROJECT_ID=$DEV_PROJECT_ID" > .env

# 0b. Verify the file has a non-empty value
cat .env   # must show: DEV_PROJECT_ID=<digits>, NOT empty

# 0c. Ensure vite.config.ts has loadEnv + define (see pitfall #1 Step 2)

# 0d. Build and verify projectId is embedded (HARD GATE — must pass)
pnpm build && grep -c "$DEV_PROJECT_ID" dist/assets/*.js
# Expected output: a number >= 1. If 0 → FIX BEFORE PROCEEDING.
```

> If Step 0d returns `0`, **stop**. The integration is broken. Go back to pitfall #1 and fix the `.env` file / `vite.config.ts` before writing any payment UI code. Proceeding with an empty projectId guarantees `"projectId must not be blank"` errors at runtime.

1. Ensure `projectId` is injected at build time and **verified** (Step 0 above), and `vite.config.ts` has `base: './'` (pitfall #4).
2. Decide the amount (in **fen**) and a short `subject` for the order.
3. Frontend calls `POST /api/pay/general/cashier` with `{ projectId, orderAmount, subject, frontNotifyUrl }` — `frontNotifyUrl` has **no hash**, just a `?from=pay-result` flag (pitfall #2). In iframe dev preview this URL is only for the **popup** cashier return; the iframe page does not navigate there.
4. **`stashPendingOrder(...)` before opening cashier** (both paths — pitfall #3).
5. **Open cashier — two paths (pitfall #6, MANDATORY):**
   - **iframe dev preview:** `openCashier(cashierUrl)` → `window.open` — iframe **never** navigates away
   - **standalone / prod:** `window.location.href = cashierUrl`
6. **Wait for result — two paths:**
   - **iframe dev preview:** poll `/status` on the **pay page**; on `PAID` show success in place, `clearPendingOrder`, idempotent business write (pitfall #5).
   - **standalone / prod:** cashier redirects to `frontNotifyUrl` → pre-router guard → result page → poll → business write (steps 7–9 below).

**Standalone / prod return-redirect path only (steps 7–9):**

7. Pre-router guard (module scope, before router — pitfall #2) rewrites `?from=pay-result` to `#/pay-result`.
8. Result page: recover `gatewayOrderNo` from storage (pitfall #3) → poll `/status`.
9. On `PAID`: idempotent business write (pitfall #5) + cleanup stash.

**Step 10 — Self-check (HARD GATE):**

**Path A — iframe dev preview (MANDATORY if testing in platform preview):**
1. Click Pay → a **new popup window** opens the cashier (iframe URL unchanged).
2. Pay page shows "等待支付…" / polling state; Network tab shows repeated `POST /api/pay/general/status`.
3. After real or mocked PAID, success UI appears **on the pay page inside iframe** without iframe navigation.
4. `pendingOrder:*` keys cleared after PAID.

**Path B — standalone return flow (MANDATORY before prod publish):**
```text
Shape B — standalone return URL simulation (flag only, no order number) — MANDATORY before prod:
  http://localhost:5173/?from=pay-result
  PRECONDITION: stash in storage first:
    localStorage.setItem('pendingOrder:TEST_GW_123',
      JSON.stringify({gatewayOrderNo:'TEST_GW_123',amount:100,name:'test'}))
```

For Path B you MUST observe (all three):

1. Address bar ends in `#/pay-result`; result page renders polling state; `/status` polls fire.

> **Pass = Path A (iframe popup + in-place result) AND Path B (standalone return URL).** Do not ship if iframe dev uses `location.href` to cashier.

### Custom UI integration (H5 mode — mobile/system browser only)

> Prerequisite: Step 0. **Only for non-App clients** — if `isAppWebView()` use App native flow (pitfall #7). **iframe dev preview uses the same two-path rule (pitfall #6):** poll on stay-page for iframe; return-redirect for standalone.

1. Frontend calls `POST /api/pay/general/h5/create-order` — keep `orderNo` and `gatewayOrderNo`.
2. Show channel picker. **`stashPendingOrder()` before invoking channel.**
3. **iframe dev preview:** open channel in new window when possible (`window.open(redirectUrl)` for `REDIRECT_URL`; for `FORM_HTML` write form into a popup via `window.open('about:blank')` then `document.write(formHtml)`). Poll `/status` on pay page; show result in place; `clearPendingOrder` on PAID.
4. **standalone / prod:** invoke channel in same window (`formHtml` inject or `location.href = redirectUrl`); after relay returns to `frontNotifyUrl`, use return page + guard + poll (pitfall #2–#5).
5. Pass `frontNotifyUrl` to `/h5/pay` in both paths (relay URL for channel; iframe page ignores the return).

**Step 10:** Path A (iframe popup + poll) + Path B (standalone H5 return) both required.

### App native integration (App WebView only)

> Prerequisite: Step 0. **Only when `isAppWebView()` is true.** Never call H5 endpoints from App (9383). Full guide: `App 原生支付.md` in pay-web module.

1. Before pay, confirm `isAppWebView()` — if false, use H5 or cashier instead.
2. `POST /api/pay/general/app/create-order` — keep `orderNo` and `gatewayOrderNo`.
3. `stashPendingOrder()` before invoking pay.
4. `POST /api/pay/general/app/pay` with `{ orderNo, payChannel }`.
5. **`invokeAppPay(data)`** (pitfall #7):
   - **WxPay + `redirectUrl` starts with `weixin://`:** `parseAnxinfuMiniProgramParam` → `wx.miniapp.launchMiniProgram`（`userName=gh_cd6acad9a40d`, `path=ipay/main?{param}`）
   - **WxPay + `wxPayParams`:** `invokeWxPrepay`（其他渠道兼容）
   - **AliPay + `REDIRECT_URL`:** `openPayUrl(redirectUrl)`（如 `https://qr.alipay.com/...`）
   - **AliPay + `QRCODE_FALLBACK`:** `showQrCode(qrCodeContent)`
6. SDK / 小程序回调后 poll `POST /api/pay/general/status` until `PAID` — **不要**仅依赖 `launchMiniProgram` 的 `success`。
7. On PAID: idempotent business write + `clearPendingOrder`.

---

## Frontend integration templates

### Amount conversion

The API takes amount in **fen** (分). Convert RMB to fen with `Math.round(rmb * 100)` — never use `rmb * 100` directly (floating-point: `0.1 + 0.2`).

### Build-time `projectId` + status helpers (shared by all templates)

All templates below assume you have injected `projectId` at build time as a global constant (pitfall #1). They read `PROJECT_ID` and poll status from a small helper module:

```ts
// pay.ts
declare const __APP_PROJECT_ID__: string;
export const PROJECT_ID = typeof __APP_PROJECT_ID__ !== 'undefined' ? __APP_PROJECT_ID__ : '';

const PENDING_LATEST_KEY = 'pendingOrder:latest';

export const PAY_HEADER_CLIENT_TYPE = 'X-Client-Type';

function isAppFromClientTypeValue(clientType: string): boolean {
  const n = clientType.trim().toLowerCase();
  if (['web', 'h5', 'browser', 'wap'].includes(n)) return false;
  if (n.startsWith('app') || ['native', 'ios', 'android', 'mobile', 'mobile-app', 'nuwax-app'].includes(n)) return true;
  return true;
}

function isAppFromUserAgent(): boolean {
  const ua = navigator.userAgent || '';
  if (/NuwaxApp|NUWAX_APP|nuwax-app/i.test(ua)) return true;
  if (/nuwax/i.test(ua) && (/webview|;\s*wv\)/i.test(ua))) return true;
  return false;
}

export function resolveClientTypeHeader(): string | undefined {
  const fromBridge = (window as any).__NUWAX_CLIENT_TYPE__ as string | undefined;
  if (fromBridge?.trim()) return fromBridge.trim();
  if (isAppFromUserAgent()) return 'app';
  return undefined;
}

/** App WebView — must use /app/* only (pitfall #7) */
export function isAppWebView(): boolean {
  const ct = resolveClientTypeHeader();
  if (ct) return isAppFromClientTypeValue(ct);
  return isAppFromUserAgent();
}

/** All pay API calls MUST use this — sends X-Client-Type for backend 9383/9384 guards */
export function payFetch(input: RequestInfo, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const clientType = resolveClientTypeHeader();
  if (clientType) headers.set(PAY_HEADER_CLIENT_TYPE, clientType);
  return fetch(input, { ...init, headers });
}

/** Dev preview: project runs inside platform iframe (pitfall #6) */
export function isEmbeddedPreview() {
  try { return window.self !== window.top; } catch { return true; }
}

export function buildFrontNotifyUrl() {
  return window.location.origin + window.location.pathname + '?from=pay-result';
}

export function stashPendingOrder(pending: Record<string, unknown>) {
  const json = JSON.stringify(pending);
  const gw = String(pending.gatewayOrderNo ?? '');
  localStorage.setItem(`pendingOrder:${gw}`, json);
  localStorage.setItem(PENDING_LATEST_KEY, json);
  try { sessionStorage.setItem('pendingOrder', json); } catch { /* ignore */ }
}

export function clearPendingOrder(gatewayOrderNo: string) {
  try { sessionStorage.removeItem('pendingOrder'); } catch { /* ignore */ }
  localStorage.removeItem(`pendingOrder:${gatewayOrderNo}`);
  localStorage.removeItem(PENDING_LATEST_KEY);
}

/** iframe → window.open; standalone → location.href (pitfall #6) */
export function openCashier(cashierUrl: string) {
  if (isEmbeddedPreview()) {
    const w = window.open(cashierUrl, '_blank', 'noopener,noreferrer');
    if (!w) throw new Error('弹窗被拦截，请允许弹出窗口后重试');
    return w;
  }
  window.location.href = cashierUrl;
  return null;
}

export function recoverPendingFromLocalStorage() {
  try {
    const latest = JSON.parse(localStorage.getItem(PENDING_LATEST_KEY) || 'null');
    if (latest?.gatewayOrderNo) return latest;
  } catch { /* ignore */ }
  const entries: Record<string, unknown>[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key || !key.startsWith('pendingOrder:') || key === PENDING_LATEST_KEY) continue;
    try {
      const o = JSON.parse(localStorage.getItem(key) || 'null');
      if (o?.gatewayOrderNo) entries.push(o);
    } catch { /* ignore */ }
  }
  return entries.length ? entries[entries.length - 1] : null;
}

/** Standalone return page only (pitfall #3) */
export function resolvePendingOrder() {
  let stashed: Record<string, unknown> | null = null;
  try { stashed = JSON.parse(sessionStorage.getItem('pendingOrder') || 'null'); } catch { /* */ }
  if (!stashed?.gatewayOrderNo) stashed = recoverPendingFromLocalStorage();
  if (!stashed || typeof stashed !== 'object' || !stashed.gatewayOrderNo) return null;
  return stashed;
}

const API = {
  cashier: '/api/pay/general/cashier',
  h5CreateOrder: '/api/pay/general/h5/create-order',
  h5Pay: '/api/pay/general/h5/pay',
  appCreateOrder: '/api/pay/general/app/create-order',
  appPay: '/api/pay/general/app/pay',
  status: '/api/pay/general/status',
};

export async function createH5Order({ amountInFen, subject, bizOrderNo }: { amountInFen: number; subject: string; bizOrderNo?: string }) {
  if (isAppWebView()) throw new Error('H5 pay not allowed in App — use app/create-order');
  const res = await payFetch(API.h5CreateOrder, {
    method: 'POST',
    body: JSON.stringify({ projectId: PROJECT_ID, orderAmount: amountInFen, subject, bizOrderNo }),
  });
  const json = await res.json();
  if (json.code !== '0000') throw new Error(json.message || 'create H5 order failed');
  return json.data as { orderNo: string; gatewayOrderNo: string };
}

export async function createAppOrder({ amountInFen, subject, bizOrderNo }: { amountInFen: number; subject: string; bizOrderNo?: string }) {
  if (!isAppWebView()) throw new Error('App native pay requires App WebView');
  const res = await payFetch(API.appCreateOrder, {
    method: 'POST',
    body: JSON.stringify({ projectId: PROJECT_ID, orderAmount: amountInFen, subject, bizOrderNo }),
  });
  const json = await res.json();
  if (json.code !== '0000') throw new Error(json.message || 'create app order failed');
  return json.data as { orderNo: string; gatewayOrderNo: string };
}

export async function createCashierOrder({ amountInFen, subject, frontNotifyUrl }) {
  const res = await payFetch(API.cashier, {
    method: 'POST',
    body: JSON.stringify({ projectId: PROJECT_ID, orderAmount: amountInFen, subject, frontNotifyUrl }),
  });
  const json = await res.json();
  if (json.code !== '0000') throw new Error(json.message || 'create order failed');
  return json.data; // { orderNo, gatewayOrderNo, cashierUrl }
}

// Query payment status (safe to poll — see pollStatus below)
export async function queryStatus(gatewayOrderNo) {
  const res = await payFetch(API.status, {
    method: 'POST',
    body: JSON.stringify({ gatewayOrderNo }),
  });
  const json = await res.json();
  if (json.code !== '0000') throw new Error(json.message || 'status query failed');
  return json.data;
}

/**
 * Poll status until terminal (PAID/FAILED/CLOSED) or timeout.
 * Status polling is MANDATORY — payment is async; "user returned" ≠ "paid".
 */
export function pollStatus(gatewayOrderNo, { onPaid, onFailed, onUpdate } = {}, intervalMs = 2500, timeoutMs = 60 * 60 * 1000) {
  let stop = false;
  const start = Date.now();
  (async () => {
    while (!stop && Date.now() - start < timeoutMs) {
      try {
        const info = await queryStatus(gatewayOrderNo);
        onUpdate?.(info);
        if (info.status === 'PAID') { onPaid?.(info); return; }
        if (info.status === 'FAILED' || info.status === 'CLOSED') { onFailed?.(info); return; }
      } catch {
        // single query failure — keep polling
      }
      await new Promise((r) => setTimeout(r, intervalMs));
    }
    if (!stop) onFailed?.({ status: 'CLOSED' });
  })();
  return () => { stop = true; };
}

/** iframe path: poll on pay page after openCashier (pitfall #6) */
export function pollUntilPaid(
  gatewayOrderNo: string,
  { onPaid, onFailed, onUpdate }: { onPaid?: (info: unknown) => void; onFailed?: (info: unknown) => void; onUpdate?: (info: unknown) => void } = {},
  intervalMs = 2500,
  timeoutMs = 60 * 60 * 1000,
) {
  return pollStatus(gatewayOrderNo, {
    onPaid: (info) => { onPaid?.(info); },
    onFailed,
    onUpdate,
  }, intervalMs, timeoutMs);
}
```

### Cashier mode — unified pay entry (framework-agnostic)

```js
// pay-with-cashier.js — use this from PayButton; handles both paths (pitfall #6)
async function payWithCashier({ amountInFen, subject, name, message, onPolling, onPaid, onFailed }) {
  const frontNotifyUrl = buildFrontNotifyUrl();
  const { gatewayOrderNo, cashierUrl } = await createCashierOrder({ amountInFen, subject, frontNotifyUrl });
  const pending = { gatewayOrderNo, amountInFen, subject, name, message };
  stashPendingOrder(pending);

  if (isEmbeddedPreview()) {
    // iframe dev: popup + poll on this page — never location.href
    openCashier(cashierUrl);
    onPolling?.();
    return pollUntilPaid(gatewayOrderNo, {
      onPaid: async (info) => {
        await onPaid?.(info, pending);
        clearPendingOrder(gatewayOrderNo);
      },
      onFailed,
    });
  }

  // standalone / prod: same-window redirect — result on return URL
  window.location.href = cashierUrl;
}
```

### Cashier mode — standalone return page poll (prod only)

```js
// 2. on the return page (standalone path only), poll until paid
```

### React (cashier mode + hash return guard + idempotent result page)

```jsx
// App.tsx — rewrite return URL in MODULE SCOPE before router (standalone return path only — pitfall #2)
import { createHashRouter, RouterProvider } from 'react-router-dom';
import { ROUTES } from './router';

const PAY_FLAG = 'from';
const PAY_FLAG_VALUE = 'pay-result';
const PAY_PARAMS = ['orderNo', 'gatewayOrderNo', 'status', 'payChannel', 'paidAt'];
function isPayReturn(sp) {
  if (sp.get(PAY_FLAG) === PAY_FLAG_VALUE) return true;
  return PAY_PARAMS.some((k) => !!sp.get(k)); // don't depend on the flag alone
}

(function normalizePayReturnUrl() {
  const url = new URL(window.location.href);
  const hashSp = new URLSearchParams((url.hash.split('?')[1]) || '');
  const source = isPayReturn(url.searchParams) ? url.searchParams
    : isPayReturn(hashSp) ? hashSp : null;
  if (!source) return;
  const pairs = [];
  for (const key of PAY_PARAMS) {
    const v = source.get(key);
    if (v) pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(v)}`);
  }
  source.forEach((v, k) => {
    if (k === PAY_FLAG || PAY_PARAMS.includes(k)) return;
    pairs.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
  });
  url.search = '';
  url.hash = `#/pay-result${pairs.length ? `?${pairs.join('&')}` : ''}`;
  window.history.replaceState(null, '', url.toString());
})();

const router = createHashRouter(ROUTES); // constructed AFTER the rewrite

export default function App() {
  return <RouterProvider router={router} />;
}
```

```jsx
// PayButton.tsx — handles iframe (popup+poll) and standalone (redirect) via payWithCashier
import { useState, useRef } from 'react';
import { payWithCashier } from './pay-with-cashier';
import { createBusinessRecord } from './pay';

export default function PayButton({ amountInFen, subject, name, message }) {
  const [loading, setLoading] = useState(false);
  const [phase, setPhase] = useState('idle'); // idle | polling | paid | failed
  const stopPollRef = useRef(null);

  const handlePay = async () => {
    setLoading(true);
    setPhase('idle');
    try {
      stopPollRef.current = await payWithCashier({
        amountInFen, subject, name, message,
        onPolling: () => { setPhase('polling'); setLoading(false); },
        onPaid: async (info, pending) => {
          const gw = pending.gatewayOrderNo;
          const KEY = `paid:${gw}`;
          if (localStorage.getItem(KEY) !== '1') {
            localStorage.setItem(KEY, '1');
            await createBusinessRecord({
              gatewayOrderNo: gw,
              amount: pending.amount ?? info.orderAmount ?? 0,
              name: pending.name ?? 'anonymous',
              message: pending.message ?? '',
            });
          }
          setPhase('paid');
        },
        onFailed: () => setPhase('failed'),
      });
    } catch (e) {
      setPhase('failed');
      setLoading(false);
    }
  };

  return (
    <div className="pay-page">
      <button disabled={loading || phase === 'polling'} onClick={handlePay}>
        {loading ? '处理中…' : phase === 'polling' ? '等待支付完成…' : `支付 ¥${(amountInFen / 100).toFixed(2)}`}
      </button>
      {phase === 'paid' && <p>支付成功</p>}
      {phase === 'failed' && <p style={{ color: 'red' }}>支付未完成或已取消</p>}
    </div>
  );
}
```

```jsx
// PayResult.tsx — standalone / prod return page ONLY (pitfall #2–#5)
// iframe dev preview shows result on PayButton via poll — this route is not used there.
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { pollStatus, resolvePendingOrder, createBusinessRecord } from './pay';

// gatewayOrderNo from storage (iframe: localStorage-first — pitfall #6)
function resolveGatewayOrderNoFromUrl(searchParams) {
  const hq = new URLSearchParams((window.location.hash.split('?')[1]) || '');
  const q = new URLSearchParams(window.location.search);
  return hq.get('gatewayOrderNo') || q.get('gatewayOrderNo')
    || hq.get('orderNo') || q.get('orderNo')
    || searchParams.get('gatewayOrderNo') || searchParams.get('orderNo') || '';
}

export default function PayResult() {
  const [state, setState] = useState('pending'); // pending | paid | failed
  const [searchParams] = useSearchParams();

  useEffect(() => {
    const stashed = resolvePendingOrder();
    const gatewayOrderNo =
      stashed?.gatewayOrderNo || resolveGatewayOrderNoFromUrl(searchParams);
    if (!gatewayOrderNo) { setState('failed'); return; }

    // idempotency key by gatewayOrderNo, PERSISTED (pitfall #5)
    const KEY = `paid:${gatewayOrderNo}`;
    const stop = pollStatus(
      gatewayOrderNo,
      {
        onPaid: async (info) => {
          if (localStorage.getItem(KEY) !== '1') {
            localStorage.setItem(KEY, '1');            // reserve first
            try {
              // do NOT gate the whole write on `stashed` — fall back to the
              // status payload so a missing stash never drops a paid order's
              // record (pitfall #5).
              await createBusinessRecord({
                gatewayOrderNo,
                amount: stashed?.amount ?? info.orderAmount ?? 0,
                name: stashed?.name ?? 'anonymous',
                message: stashed?.message ?? '',
              });
              sessionStorage.removeItem('pendingOrder');
              localStorage.removeItem(`pendingOrder:${gatewayOrderNo}`);
              localStorage.removeItem('pendingOrder:latest');
            } catch (e) {
              localStorage.removeItem(KEY);             // roll back on failure
              console.error(e);
              setState('paid');
              return; // surface failure instead of silently losing the record
            }
          }
          setState('paid');
        },
        onFailed: () => setState('failed'),
      },
      2500,
      60 * 60 * 1000
    );
    return () => stop();
  }, []);

  if (state === 'paid') return <div className="pay-result-page"><h1>支付成功</h1></div>;
  if (state === 'failed') return <div className="pay-result-page"><h1>支付未完成或已取消</h1></div>;
  return <div className="pay-result-page"><h1>支付结果确认中…</h1></div>;
}
```

### Vue 3 (cashier mode + hash return guard)

```ts
// main.ts — rewrite the return URL BEFORE the router is created (pitfall #2).
// Do NOT put this in App.vue onMounted — the router reads the hash at
// createRouter time, which is before any component mounts.
import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'

;(function normalizePayReturnUrl() {
  const url = new URL(window.location.href)
  if (url.searchParams.get('from') !== 'pay-result') return
  const keep = ['orderNo', 'gatewayOrderNo', 'status', 'payChannel', 'paidAt']
  const pairs: string[] = []
  url.searchParams.forEach((v, k) => {
    if (k === 'from' || !keep.includes(k)) return
    pairs.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
  })
  url.search = ''
  url.hash = `#/pay-result${pairs.length ? `?${pairs.join('&')}` : ''}`
  window.history.replaceState(null, '', url.toString())
})()

const router = createRouter({ history: createWebHashHistory(), routes: [/* ... */] })
createApp(App).use(router).mount('#app')
```

> The Vue `App.vue` itself needs **no** return-guard logic — keep it a plain `<RouterView />`. The guard lives in `main.ts` (or wherever the router is constructed), before `createRouter`.

```vue
<!-- PayButton.vue -->
<script setup>
import { ref } from 'vue'
import { createCashierOrder, buildFrontNotifyUrl, stashPendingOrder, openCashier, isEmbeddedPreview, pollUntilPaid, clearPendingOrder } from './pay'
const props = defineProps({ amountInFen: Number, subject: String })
const loading = ref(false)
const phase = ref('idle')
let stopPoll = null

async function pay() {
  loading.value = true
  phase.value = 'idle'
  try {
    const frontNotifyUrl = buildFrontNotifyUrl()
    const { gatewayOrderNo, cashierUrl } = await createCashierOrder({
      amountInFen: props.amountInFen,
      subject: props.subject,
      frontNotifyUrl,
    })
    stashPendingOrder({ gatewayOrderNo, amountInFen: props.amountInFen, subject: props.subject })
    if (isEmbeddedPreview()) {
      openCashier(cashierUrl)
      phase.value = 'polling'
      loading.value = false
      stopPoll = pollUntilPaid(gatewayOrderNo, {
        onPaid: () => { clearPendingOrder(gatewayOrderNo); phase.value = 'paid' },
        onFailed: () => { phase.value = 'failed' },
      })
    } else {
      window.location.href = cashierUrl
    }
  } catch (e) {
    phase.value = 'failed'
    loading.value = false
  }
}
</script>

```vue
<template>
  <div class="pay-page">
    <button :disabled="loading || phase === 'polling'" @click="pay">
      {{ loading ? '处理中…' : phase === 'polling' ? '等待支付完成…' : `支付 ¥${(amountInFen / 100).toFixed(2)}` }}
    </button>
    <p v-if="phase === 'paid'">支付成功</p>
    <p v-if="phase === 'failed'" style="color: red">支付未完成或已取消</p>
  </div>
</template>
```

> Vue `PayResult` is for **standalone return URL only**. iframe dev shows result on the pay button page.

### H5 mode — create order, stash, invoke channel

```js
/** iframe: open channel in popup; standalone: same window (pitfall #6) */
function openH5Channel({ invokeType, formHtml, redirectUrl }) {
  if (isEmbeddedPreview()) {
    if (invokeType === 'REDIRECT_URL' && redirectUrl) {
      const w = window.open(redirectUrl, '_blank', 'noopener,noreferrer');
      if (!w) throw new Error('弹窗被拦截，请允许弹出窗口后重试');
      return;
    }
    if (invokeType === 'FORM_HTML' && formHtml) {
      const w = window.open('about:blank', '_blank', 'noopener,noreferrer');
      if (!w) throw new Error('弹窗被拦截，请允许弹出窗口后重试');
      w.document.write(formHtml);
      w.document.close();
      return;
    }
    if (redirectUrl) window.open(redirectUrl, '_blank', 'noopener,noreferrer');
    return;
  }
  if (invokeType === 'FORM_HTML' && formHtml) {
    document.body.insertAdjacentHTML('beforeend', formHtml);
  } else if (redirectUrl) {
    window.location.href = redirectUrl;
  }
}

async function payWithH5({ amountInFen, subject, payChannel, name, message, onPolling, onPaid, onFailed }) {
  if (isAppWebView()) throw new Error('H5 pay not allowed in App — use payWithAppNative');
  const { orderNo, gatewayOrderNo } = await createH5Order({ amountInFen, subject });
  const frontNotifyUrl = buildFrontNotifyUrl();
  const pending = { gatewayOrderNo, amountInFen, subject, name, message };
  stashPendingOrder(pending);

  const res = await payFetch('/api/pay/general/h5/pay', {
    method: 'POST',
    body: JSON.stringify({ orderNo, payChannel, frontNotifyUrl }),
  });
  const json = await res.json();
  if (json.code !== '0000') throw new Error(json.message);
  const { invokeType, formHtml, redirectUrl } = json.data;

  if (isEmbeddedPreview()) {
    openH5Channel({ invokeType, formHtml, redirectUrl });
    onPolling?.();
    return pollUntilPaid(gatewayOrderNo, {
      onPaid: async (info) => { await onPaid?.(info, pending); clearPendingOrder(gatewayOrderNo); },
      onFailed,
    });
  }

  openH5Channel({ invokeType, formHtml, redirectUrl }); // standalone — leaves page
}
```

> **iframe dev:** poll on pay page, show result in place. **standalone:** channel returns via relay → `frontNotifyUrl` → result page flow.

### App native mode — invokeAppPay + poll

> Copy `parseAnxinfuMiniProgramParam`, `launchAnxinfuWxPay`, `invokeAppPay` from pitfall #7. WebView 壳无 `wx.miniapp` 时，原生需封装 `launchMiniProgram` JSBridge，参数与上文一致。

```js
async function payWithAppNative({ amountInFen, subject, payChannel, name, message, onPolling, onPaid, onFailed }) {
  if (!isAppWebView()) throw new Error('App native pay requires App WebView');

  const { orderNo, gatewayOrderNo } = await createAppOrder({ amountInFen, subject });
  const pending = { gatewayOrderNo, amountInFen, subject, name, message };
  stashPendingOrder(pending);

  const res = await payFetch('/api/pay/general/app/pay', {
    method: 'POST',
    body: JSON.stringify({ orderNo, payChannel }),
  });
  const json = await res.json();
  if (json.code !== '0000') throw new Error(json.message);
  const pay = { ...json.data, payChannel: json.data.payChannel ?? payChannel };

  await invokeAppPay(pay);

  onPolling?.();
  return pollUntilPaid(gatewayOrderNo, {
    onPaid: async (info) => { await onPaid?.(info, pending); clearPendingOrder(gatewayOrderNo); },
    onFailed,
  });
}
```

> App native has no `frontNotifyUrl`. **`launchMiniProgram` success ≠ paid** — poll `/status`.

---

## Best practices

- **Amount unit is always fen (分)**. `orderAmount: 100` = 1.00 RMB. Convert with `Math.round(rmb * 100)` to avoid floating-point drift.
- **Client routing (pitfall #7 — MANDATORY):** `isAppWebView()` → `/app/*` only. Mobile/system browser custom UI → `/h5/*`. **All pay APIs via `payFetch`** (auto `X-Client-Type`). Never H5 in App (9383) or App API in browser (9384).
- **Two-path checkout (pitfall #6 — MANDATORY):** iframe dev → `window.open(cashierUrl)` + poll on pay page + in-place result + `clearPendingOrder` on PAID. Standalone/prod → `location.href = cashierUrl` + return via `frontNotifyUrl`. **Never `location.href` to cashier inside iframe.**
- **Poll `/status` — MANDATORY in both paths.** iframe: poll on pay page while popup is open. standalone: poll on result page after cashier return.
- **Poll interval 2–3 seconds**; cap at ~1 hour. Stop on `PAID`/`FAILED`/`CLOSED`.
- **Inject `projectId` at build time** — see pitfall #1.
- **`frontNotifyUrl` has no `#` hash** — pitfall #2. Drives standalone return; iframe dev passes it for popup cashier only.
- **Stash before pay (`stashPendingOrder`); clear after PAID on iframe path (`clearPendingOrder`).** Standalone result page uses `resolvePendingOrder` (pitfall #3).
- **H5:** mobile/system browser only — same two-path rule; popup + poll in iframe; same-window + return URL in standalone.
- **App native:** App WebView only — `/app/create-order` → `/app/pay` → **`invokeAppPay`**（微信 `weixin://` → `launchMiniProgram`；支付宝 → `openPayUrl`）→ poll `/status`；`launchMiniProgram` success 不算支付成功。
- **Keep `/api/*` absolute**; set Vite `base: './'` (pitfall #4).
- **`gatewayOrderNo` is your handle** for `/status`, recovery, and idempotency (pitfall #3/#5).
- **Business write on `PAID` must be idempotent** by `gatewayOrderNo` (pitfall #5).
- **Persist `gatewayOrderNo` in your business data table** — and SELECT it in read APIs (pitfall #5).
- **Never put a Bearer token in frontend code.**
- **H5 `invokeType`:** iframe opens channel in popup; standalone uses formHtml inject or `location.href`.
- **Order IDs are server-generated** — do not invent `orderNo`.
