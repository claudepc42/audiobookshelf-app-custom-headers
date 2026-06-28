# Custom Headers — Design Document
## Audiobookshelf Android App

**Purpose:** Enable custom HTTP request headers (e.g. Cloudflare Zero Trust service tokens) on every request the app makes, so the app works behind header-authenticated reverse proxies.

---

## Background

`ServerConnectionConfig` already has a `customHeaders: Map<String, String>?` field (`DeviceClasses.kt` line 36–47). The UI modal (`CustomHeadersModal.vue`) already exists and works. The data persists through `AbsDatabase.kt`. The feature was designed but never fully wired to the HTTP stacks the app uses.

---

## Architecture: HTTP Stacks Patched

The app has five independent HTTP layers, all of which forward custom headers:

| Stack | Files | Handles |
|---|---|---|
| Capacitor/JS | `plugins/nativeHttp.js`, `components/connection/ServerConnectForm.vue` | Login, library browsing, API calls from the WebView, token refresh |
| Native Kotlin API | `android/.../server/ApiHandler.kt` | Background sync, play requests, progress reporting, Android Auto, Kotlin-layer token refresh |
| ExoPlayer streaming | `android/.../player/PlayerNotificationService.kt` | Audio file streaming (direct play and HLS) |
| WebSocket | `plugins/server.js` | Live sync and progress push events |
| Download manager | `android/.../managers/InternalDownloadManager.kt`, `android/.../managers/DownloadItemManager.kt` | Offline downloads |

---

## What the Patch Does

**8 files changed.**

### 1. `components/connection/ServerConnectForm.vue`

- **UI button** added below the server address submit button: `<a @click="addCustomHeaders">Custom Headers</a>`. This opens the existing `CustomHeadersModal.vue`. Without this, headers can never be set by the user.
- **`getServerAddressStatus()`**: passes `this.serverConfig.customHeaders` to `getRequest()` so the `/status` probe uses headers.
- **`connectToServer()`**: passes `config.customHeaders` to `pingServerAddress()` so the reconnect ping uses headers.
- **`oauthRequest()`**: adds `headers: this.serverConfig.customHeaders || {}` to the `CapacitorHttp.get()` call so the OAuth flow works behind CF Zero Trust.

### 2. `plugins/nativeHttp.js`

- **`request()`**: after merging `options.headers`, spreads `serverConnectionConfig.customHeaders` into the headers object. This covers every post-login API call made through the shared nativeHttp layer automatically.
- **`handleTokenRefresh()`**: passes the full `serverConnectionConfig` (not just `.address`) to `refreshAccessToken()` so the token refresh request also carries custom headers.
- **`refreshAccessToken()`**: signature changed from `(refreshToken, serverAddress)` to `(refreshToken, serverConnectionConfig)`. Extracts `.address` internally and spreads `customHeaders` into the refresh request headers.

### 3. `android/.../server/ApiHandler.kt`

- **`getRequest()`**: reads `config?.customHeaders ?: DeviceManager.serverConnectionConfig?.customHeaders`, loops over them with `builder.addHeader()`.
- **`postRequest()`**: same treatment.
- **`patchRequest()`**: reads from `DeviceManager.serverConnectionConfig?.customHeaders`, same loop.
- **`handleTokenRefresh()`**: `refreshRequest` builder now loops `DeviceManager.serverConnectionConfig?.customHeaders` via `addHeader()` before `.build()`. This covers the Kotlin-layer `/auth/refresh` retry path.

### 4. `android/.../player/PlayerNotificationService.kt`

- **Direct play path**: after `setUserAgent()`, builds `directPlayHeaders` with `Authorization` + custom headers, calls `setDefaultRequestProperties()`.
- **HLS path**: replaces the old single-entry `hashMapOf` with `hlsHeaders` that starts with `Authorization` and has custom headers merged in via `putAll()`.

### 5. `layouts/default.vue`

- **Auto-connect race condition fix**: adds `if (!this.user) await this.attemptConnection()` immediately after `this.hasMounted = true` at the end of `mounted()`.

**Why this is needed:** On cold start, `mounted()` runs `attemptConnection()` early (line ~374). If the network isn't detected yet at that moment, `attemptConnection()` bails. The `networkConnected` watcher is the only retry path, but it's guarded by `if (!this.hasMounted) return` — so if the network comes online during `syncLocalSessions()` (which runs before `hasMounted` is set), the watcher drops the event and `attemptConnection()` never fires again. The user is left on the `/connect` screen and must manually select their server.

The fix re-runs `attemptConnection()` once `hasMounted` is safely set. It's safe to call twice: `attemptConnection()` has its own `attemptingConnection` boolean guard that prevents concurrent execution, and it no-ops immediately if `this.user` is already set.

### 6. `plugins/server.js`

- **`connect()`**: reads `this.$store.state.user.serverConnectionConfig?.customHeaders` and passes it as `extraHeaders` in the socket.io options object. This ensures the WebSocket upgrade request carries custom headers for CF Zero Trust.

### 7. `android/.../managers/InternalDownloadManager.kt`

- **`download()`**: signature changed from `download(url: String)` to `download(url: String, customHeaders: Map<String, String>? = null)`. Loops `customHeaders` onto the `Request.Builder` via `addHeader()`.

### 8. `android/.../managers/DownloadItemManager.kt`

- **`startInternalDownload()`**: passes `DeviceManager.serverConnectionConfig?.customHeaders` as the second argument to `InternalDownloadManager.download()`.

---

## Header Precedence

In `nativeHttp.js`, the merge order is:
1. `Authorization: Bearer <token>` (set first)
2. `options.headers` (passed by caller — can override Authorization)
3. `serverConnectionConfig.customHeaders` (applied last — can override anything above)

This means a user-set custom header named `Authorization` would override the bearer token. This is intentional for advanced use cases. In the Kotlin stacks, `addHeader()` adds duplicate headers rather than replacing — OkHttp sends all of them, and the server typically uses the last one.

---

## How to Re-Apply After an Upstream Update

1. Pull the new upstream HEAD.
2. Run `git diff HEAD~1 HEAD` on each of the 8 patched files to check if the patched functions changed upstream.
3. If unchanged, `git apply` the saved patch directly.
4. If changed, re-apply manually per file:
   - **`ApiHandler.kt`**: find each `Request.Builder()` call in `getRequest`, `postRequest`, `patchRequest`, and `handleTokenRefresh`. Convert to `builder` variable, add the `customHeaders?.forEach` loop before `.build()`.
   - **`PlayerNotificationService.kt`**: find both `dataSourceFactory.setDefaultRequestProperties(...)` calls in `preparePlayer`. Replace with a `hashMapOf` + `putAll(customHeaders)` pattern.
   - **`nativeHttp.js`**: the customHeaders block goes immediately after the `options.headers` merge block. The `refreshAccessToken` signature change is a two-line touch.
   - **`ServerConnectForm.vue`**: the template button is a three-line addition after the submit div. The three method tweaks (`getServerAddressStatus`, `connectToServer`, `oauthRequest`) are one-line each.
   - **`layouts/default.vue`**: one line after `this.hasMounted = true` at the end of `mounted()`.
   - **`server.js`**: one line reading `customHeaders` from the store, added before `socketOptions`, plus `extraHeaders: customHeaders` inside the options object.
   - **`InternalDownloadManager.kt`**: add `customHeaders: Map<String, String>? = null` parameter to `download()`, add `builder` variable pattern, loop headers before `.build()`.
   - **`DownloadItemManager.kt`**: add `DeviceManager.serverConnectionConfig?.customHeaders` as second argument to `InternalDownloadManager(...).download(...)`.

---

## Testing Checklist

- [ ] Enter server address → "Custom Headers" link appears below Submit
- [ ] Add `CF-Access-Client-Id` and `CF-Access-Client-Secret` headers → saved, visible in modal
- [ ] Login completes (headers sent on `/ping`, `/status`, `/login`)
- [ ] Library loads (headers sent via nativeHttp on all API calls)
- [ ] Direct play audio streams (headers sent via ExoPlayer DefaultHttpDataSource)
- [ ] HLS transcoded stream plays (headers sent via ExoPlayer HlsMediaSource)
- [ ] Token refresh works after expiry — JS layer (headers included in `/auth/refresh` via nativeHttp)
- [ ] Token refresh works after expiry — Kotlin layer (headers included in ApiHandler `handleTokenRefresh`)
- [ ] OpenID/OAuth login flow works (headers included in OAuth redirect request)
- [ ] WebSocket connects (headers included in socket.io `extraHeaders`)
- [ ] Offline download completes (headers included in InternalDownloadManager request)
- [ ] Custom headers survive app restart (persisted in ServerConnectionConfig via AbsDatabase)
- [ ] App auto-connects on cold start without manual server selection (validates the default.vue race condition fix)

---

## Files Changed

```
layouts/default.vue
components/connection/ServerConnectForm.vue
plugins/nativeHttp.js
plugins/server.js
android/app/src/main/java/com/audiobookshelf/app/server/ApiHandler.kt
android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt
android/app/src/main/java/com/audiobookshelf/app/managers/InternalDownloadManager.kt
android/app/src/main/java/com/audiobookshelf/app/managers/DownloadItemManager.kt
```

Base: `advplyr/audiobookshelf-app` `main` branch, commit `815cd07` / `d25e744` range (June 2026).
