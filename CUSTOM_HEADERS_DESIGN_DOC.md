# CF Zero Trust & Custom Headers — Design Document
## Audiobookshelf Android App Fork

**Purpose:** Enable Cloudflare Zero Trust authentication and custom HTTP request headers on every request the app makes, so the app works behind header-authenticated or CF-protected reverse proxies.

---

## Background

`ServerConnectionConfig` already has a `customHeaders: Map<String, String>?` field (`DeviceClasses.kt` line 36–47). The UI modal (`CustomHeadersModal.vue`) already exists and works. The data persists through `AbsDatabase.kt`. The feature was designed but never fully wired to the HTTP stacks the app uses.

---

## Features

### 1. CF Zero Trust WebView SSO

When the user submits a server address on Android with no custom headers set, the app probes the server with `disableRedirects: true`. If the server returns a 302 redirect to `cloudflareaccess.com`, CF Zero Trust is detected automatically and an in-app WebView opens for the user to authenticate with their CF identity provider.

After successful authentication, CF redirects back to the server domain. The app extracts **all cookies** from the WebView's `CookieManager` for the server host (not just `CF_Authorization`, because CF binding cookies must also travel with the auth cookie or requests will be rejected). The full cookie string is stored as `customHeaders: { Cookie: "<full-cookie-string>" }` and applied to all subsequent requests.

The user can also trigger the WebView manually via the **Login with Cloudflare** link below the Submit button.

**Re-authentication:** When the CF session expires (purely time-based JWT `exp`), requests fail with 401/403 and the user is returned to the connect screen. Reconnecting triggers the WebView again.

### 2. Custom HTTP headers (service tokens / advanced)

For CF service tokens (`CF-Access-Client-Id` / `CF-Access-Client-Secret`) or other header-gated proxies, the user taps **Custom Headers** to enter headers manually. When custom headers are already set, the CF auto-detection and WebView SSO are skipped entirely.

### 3. Auto-connect race condition fix

On cold start, if the network came online during `syncLocalSessions()`, the `networkConnected` watcher dropped the event and left the app on the connect screen. The fix re-runs `attemptConnection()` once `hasMounted` is safely set. Safe to call twice — `attemptConnection()` has its own concurrency guard.

---

## Architecture: HTTP Stacks Patched

The app has five independent HTTP layers, all of which inject custom headers:

| Stack | Files | Handles |
|---|---|---|
| Capacitor/JS | `plugins/nativeHttp.js`, `components/connection/ServerConnectForm.vue` | Login, library browsing, API calls, token refresh |
| Native Kotlin API | `android/.../server/ApiHandler.kt` | Background sync, play requests, progress reporting, Android Auto, Kotlin-layer token refresh |
| ExoPlayer streaming | `android/.../player/PlayerNotificationService.kt` | Audio file streaming (direct play and HLS) |
| WebSocket | `plugins/server.js` | Live sync and progress push events |
| Download manager | `android/.../managers/InternalDownloadManager.kt`, `android/.../managers/DownloadItemManager.kt` | Offline downloads |

---

## What the Patch Does

**10 files changed.**

### 1. `components/connection/ServerConnectForm.vue`

- **"Custom Headers" link** below the Submit button — opens `CustomHeadersModal.vue`.
- **"Login with Cloudflare" link** (Android only) — manually triggers CF WebView SSO.
- **CF auto-detection in `submit()`**: if no custom headers are set, calls `checkAndHandleCfZeroTrust()` before attempting server connection.
- **`checkAndHandleCfZeroTrust()`**: probes `<address>/status` with `disableRedirects: true`, checks for 302 to `cloudflareaccess.com`, opens WebView via `AbsCfZeroTrust`, stores result cookie string as `customHeaders.Cookie`.
- **`openCfSsoLogin()`**: manual trigger for the WebView SSO flow.
- **`getServerAddressStatus()`**: passes `this.serverConfig.customHeaders` to `getRequest()`.
- **`connectToServer()`**: passes `config.customHeaders` to `pingServerAddress()`.
- **`oauthRequest()`**: adds `headers: this.serverConfig.customHeaders || {}` to the OAuth call.

### 2. `plugins/nativeHttp.js`

- **`request()`**: spreads `serverConnectionConfig.customHeaders` into every request's headers.
- **`handleTokenRefresh()`**: passes full `serverConnectionConfig` to `refreshAccessToken()`.
- **`refreshAccessToken()`**: signature changed to accept `serverConnectionConfig` (not just address), spreads `customHeaders` into the refresh request.

### 3. `android/.../server/ApiHandler.kt`

- **`getRequest()`**, **`postRequest()`**, **`patchRequest()`**: loop `customHeaders` via `builder.addHeader()`.
- **`handleTokenRefresh()`**: loops `customHeaders` on the `/auth/refresh` retry request.

### 4. `android/.../player/PlayerNotificationService.kt`

- **Direct play**: merges custom headers into `directPlayHeaders`.
- **HLS**: merges custom headers into `hlsHeaders` via `putAll()`.

### 5. `layouts/default.vue`

- **Auto-connect fix**: `if (!this.user) await this.attemptConnection()` after `this.hasMounted = true`.

### 6. `plugins/server.js`

- **WebSocket `connect()`**: passes `customHeaders` as `extraHeaders` in socket.io options.

### 7. `android/.../managers/InternalDownloadManager.kt`

- **`download()`**: signature changed to accept `customHeaders: Map<String, String>? = null`; loops headers onto the `Request.Builder`.

### 8. `android/.../managers/DownloadItemManager.kt`

- **`startInternalDownload()`**: passes `DeviceManager.serverConnectionConfig?.customHeaders` to `InternalDownloadManager.download()`.

### 9. `android/.../plugins/AbsCfZeroTrust.kt` *(new)*

Capacitor plugin that opens a full-screen `Dialog` containing a WebView. Loads the server URL, monitors `onPageFinished` for return to the server host, extracts all cookies from `CookieManager`, resolves the Capacitor `PluginCall` with `{ cookieHeader: String }`. Handles user cancellation via dialog dismiss listener.

### 10. `plugins/capacitor/AbsCfZeroTrust.js` *(new)*

JS bridge wrapper that registers the `AbsCfZeroTrust` Capacitor plugin. Exports `AbsCfZeroTrust.openCfWebView({ serverAddress })` which returns `Promise<{ cookieHeader: string }>`.

---

## CF Zero Trust Cookie Notes

- CF Zero Trust sets a `CF_Authorization` cookie (JWT) on the domain after auth.
- CF may also set **binding cookies** (enabled by default on CF Access applications) that must accompany `CF_Authorization` or requests are rejected at CF's edge.
- The plugin extracts the **full cookie string** from `CookieManager.getCookie(url)` (e.g. `CF_Authorization=...; CF_AppSession=...`) and stores it as the `Cookie:` header value. This ensures binding cookies are always included.
- Cookie lifetime is configured by the CF Access admin (`exp` in the JWT). Expiry is time-based only; network changes do not invalidate the cookie.

---

## Header Precedence

In `nativeHttp.js`, the merge order is:
1. `Authorization: Bearer <token>` (set first)
2. `options.headers` (passed by caller)
3. `serverConnectionConfig.customHeaders` (applied last — can override anything above)

In the Kotlin stacks, `addHeader()` adds duplicate headers; OkHttp sends all of them and the server typically uses the last value.

---

## How to Re-Apply After an Upstream Update

1. Pull the new upstream HEAD.
2. Run `git diff HEAD~1 HEAD` on each patched file to check for upstream changes.
3. If unchanged, `git apply` the saved patch.
4. If changed, re-apply manually:
   - **`ApiHandler.kt`**: find each `Request.Builder()` call in `getRequest`, `postRequest`, `patchRequest`, `handleTokenRefresh`. Add `customHeaders?.forEach` loop before `.build()`.
   - **`PlayerNotificationService.kt`**: find both `setDefaultRequestProperties(...)` calls. Replace with `hashMapOf` + `putAll(customHeaders)`.
   - **`nativeHttp.js`**: customHeaders block goes after `options.headers` merge. `refreshAccessToken` signature takes `serverConnectionConfig`.
   - **`ServerConnectForm.vue`**: template links (2 lines), import `AbsCfZeroTrust`, CF detection block in `submit()`, three new methods (`openCfSsoLogin`, `checkAndHandleCfZeroTrust`), one-line tweaks to `getServerAddressStatus`, `connectToServer`, `oauthRequest`.
   - **`layouts/default.vue`**: one line after `this.hasMounted = true`.
   - **`server.js`**: `extraHeaders: customHeaders` in socket.io options.
   - **`InternalDownloadManager.kt`**: add `customHeaders` parameter, builder pattern, loop before `.build()`.
   - **`DownloadItemManager.kt`**: pass `customHeaders` as second arg to `InternalDownloadManager.download()`.
   - **`AbsCfZeroTrust.kt`**: new file — no upstream conflict.
   - **`AbsCfZeroTrust.js`**: new file — no upstream conflict.
   - **`MainActivity.kt`**: add `registerPlugin(AbsCfZeroTrust::class.java)` and import.
   - **`plugins/capacitor/index.js`**: add import and export.

---

## Testing Checklist

- [ ] Enter server address → "Custom Headers" and "Login with Cloudflare" links appear below Submit
- [ ] On CF-protected server: Submit auto-detects CF (no manual action needed) → WebView opens
- [ ] CF WebView: user logs in with identity provider → WebView closes automatically
- [ ] After CF WebView auth: connect screen proceeds to library without error
- [ ] "Login with Cloudflare" manual trigger works (enters address, taps link before Submit)
- [ ] Saved CF session: close app, re-open → cookies still in serverConfig → no WebView needed on next cold start
- [ ] CF session expiry: after expiry, reconnect → WebView opens again for fresh login
- [ ] Manual custom headers: enter service tokens → CF detection skipped, tokens sent on all requests
- [ ] Login completes with custom headers (headers sent on `/ping`, `/status`, `/login`)
- [ ] Library loads (headers sent via nativeHttp on all API calls)
- [ ] Direct play audio streams (headers sent via ExoPlayer DefaultHttpDataSource)
- [ ] HLS transcoded stream plays (headers sent via ExoPlayer HlsMediaSource)
- [ ] Token refresh works after expiry — JS layer (headers in `/auth/refresh` via nativeHttp)
- [ ] Token refresh works after expiry — Kotlin layer (headers in ApiHandler `handleTokenRefresh`)
- [ ] OpenID/OAuth login flow works (headers in OAuth redirect request)
- [ ] WebSocket connects (headers in socket.io `extraHeaders`)
- [ ] Offline download completes (headers in InternalDownloadManager request)
- [ ] Custom headers survive app restart (persisted in ServerConnectionConfig via AbsDatabase)
- [ ] App auto-connects on cold start without manual server selection (default.vue race condition fix)

---

## Files Changed

```
layouts/default.vue
components/connection/ServerConnectForm.vue
plugins/nativeHttp.js
plugins/server.js
plugins/capacitor/AbsCfZeroTrust.js           (new)
plugins/capacitor/index.js
android/app/src/main/java/com/audiobookshelf/app/MainActivity.kt
android/app/src/main/java/com/audiobookshelf/app/plugins/AbsCfZeroTrust.kt  (new)
android/app/src/main/java/com/audiobookshelf/app/server/ApiHandler.kt
android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt
android/app/src/main/java/com/audiobookshelf/app/managers/InternalDownloadManager.kt
android/app/src/main/java/com/audiobookshelf/app/managers/DownloadItemManager.kt
```

Base: `advplyr/audiobookshelf-app` `main` branch, commit `815cd07` / `d25e744` range (June 2026).
