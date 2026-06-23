# Hue Manager - Project Memory

@TASK.md - project design, motivation, and requirements specification.

## Project Overview

A Philips Hue lamp management system with:
- **server/**: Quarkus backend, compiled to a **GraalVM native image** (~86 MB binary, ~45 MB RSS at idle), managing lamp state via the Philips Hue Remote API (OAuth2)
- **composeApp/**: Compose Multiplatform UI library (Desktop JVM, Web JS/WasmJS, Android target)
- **androidApp/**: Android application module (depends on composeApp)
- **shared/**: Shared data models and constants

**Deployment:** Self-hosted on VDS with Docker + Caddy reverse proxy for HTTPS. The server runs alongside an **Ory Hydra** container (OAuth2 authorization server for MCP).

**IMPORTANT:** Philips Hue OAuth2 requires HTTPS and a valid domain name. The redirect URI must be publicly accessible over HTTPS.

## Tech Stack

- Kotlin 2.4.0
- **Quarkus 3.36.3** (server; replaced Ktor) — RESTEasy/JAX-RS, CDI (Arc), `quarkus-rest-kotlin-serialization`, `quarkus-rest-client`, `quarkus-scheduler`
- **GraalVM 21** — the sole JDK; the server compiles to a native image
- Ktor **client** 3.5.0 (composeApp only — the server no longer uses Ktor)
- Compose Multiplatform 1.11.1
- kotlinx-serialization 1.11.0, kotlinx-coroutines 1.11.0, kotlinx-datetime 0.8.0-0.6.x-compat
- jose4j (via Quarkus) for SPA session JWTs
- `quarkus-oidc` + `quarkus-oidc-proxy` 0.7.0 + Ory Hydra for MCP OAuth
- `quarkus-mcp-server-sse` 1.13.0 (replaced MCP Kotlin SDK)
- `quarkus-jdbc-sqlite` 3.0.11 / sqlite-jdbc 3.53.2.0 (persistent settings)
- Gradle 9.6.0 with version catalog; AGP 9.0.0

**Build Configuration:**
- Maven Central and Google repositories only (no JitPack needed)
- Configuration cache is **enabled**, but `:androidApp:assembleDebug` requires `--no-configuration-cache` (AGP's `androidJdkImage` jlink transform is config-cache-incompatible on GraalVM — oracle/graal#7064). The "incompatible with Gradle 10" deprecation warnings come from the AGP/Quarkus/Kotlin plugins (upstream, not fixable here).
- Android context management via shared AndroidContext singleton

## Architecture

```
Client (composeApp) ──HTTP/JWT──┐
                                ├─▶ Server (Quarkus native on VDS) ──OAuth2/REST──▶ Philips Cloud ──▶ Hue Bridge
Claude / MCP client ──MCP/OAuth─┘          │
                                           ├─ LampStateCache (in-memory, 5s refresh, all reads instant)
                                           └─ Ory Hydra (OAuth2 AS for MCP; login/consent delegated back to the server)
```

**Authentication:**
- **SPA / desktop / web / Android clients:** plain password → `POST /api/auth` returns a signed JWT (jose4j HS256). Clients send it as `Bearer`; the password is sent only at login. Validated per-request by `AuthVerifier` (`rest/RestSupport.kt`).
- **MCP clients:** OAuth via Ory Hydra (token issuance/DCR/discovery) + `quarkus-oidc` (resource server, validates Hydra JWTs on `/mcp`) + `quarkus-oidc-proxy` (fronts Hydra under the app's domain). Login/consent is delegated to the server (`auth/HydraLoginConsentResource.kt`), so the user still sees only the Hue Manager password screen. The server requires Hydra to boot.

**Bridge Connection via OAuth2:**
The server connects to the Hue bridge through Philips Cloud using OAuth2. No local network access, port forwarding, or VPN is required.

**OAuth2 Authorization Flow:**
1. Configure `HUE_REDIRECT_URI` in `.env` (must be HTTPS with a valid domain - e.g., `https://yourdomain.com/api/hue/callback`)
2. User clicks "Start Authorizing" in the app or visits `/api/hue/authorize` endpoint
3. Server generates authorization URL with proper parameters (client_id, app_id, redirect_uri)
4. App opens the URL in browser (platform-specific implementation):
   - **Desktop**: Uses `Desktop.browse()` to open system browser
   - **Android**: Uses Intent with `ACTION_VIEW` to open default browser
   - **Web**: Opens new tab/window
5. User logs in with their Philips Hue account in the browser
6. User presses the link button on their bridge when prompted
7. User completes authorization - Philips redirects to `/api/hue/callback`
8. Server handles callback, exchanges code for tokens, and persists them via `TokenStore`
9. User returns to the app and clicks "Check Again" to verify connection

**IMPORTANT OAuth2 Requirements:**
- **HTTPS is mandatory** - Philips Hue OAuth2 will not work with HTTP-only redirect URIs
- **Valid domain required** - Cannot use IP addresses or localhost for production
- **Publicly accessible** - The redirect URI must be reachable from Philips Cloud servers
- Use Caddy or similar reverse proxy to provide HTTPS with automatic Let's Encrypt certificates

The server acts as a persistent process that:
1. Maintains connection to Hue bridge via Philips Cloud
2. Runs automation (daylight simulation)
3. Provides API for the client UI
4. Handles 10-minute heartbeat for automation persistence

## Environment Variables (.env)

Config is bound via a `@ConfigMapping(prefix = "hue")` interface (`config/AppConfig.kt`), so
env vars use the `HUE_` prefix (`HUE_REGION` → `hue.region`, etc.). Quarkus reads `.env`
natively — **dotenv-kotlin was removed**. Bootstrap secrets live in `.env`; runtime-mutable
settings live in SQLite (see Persistent Settings).

```bash
# Authentication
HUE_PASSWORD_HASH=<SHA-256 hex of the password>   # no plaintext-hash auto-migration anymore
HUE_JWT_SECRET=<>= 32-byte HMAC secret for SPA session JWTs>   # e.g. openssl rand -base64 48
# HUE_JWT_TTL_DAYS=30   # optional, session token lifetime

# Location for sunrise/sunset calculation
HUE_REGION=<latitude,longitude>
HUE_PSEUDO_SUNSET=21:05
HUE_TIMEZONE=Europe/Berlin

# Persistence (SQLite) - path to runtime-settings DB (default: data/hue.db)
DATABASE_PATH=data/hue.db

# Philips Hue Remote API (OAuth2) - REQUIRED
HUE_CLIENT_ID=<from developers.meethue.com/add-new-hue-remote-api-app/>
HUE_CLIENT_SECRET=<from developers.meethue.com/add-new-hue-remote-api-app/>
HUE_APP_ID=<from developers.meethue.com/add-new-hue-remote-api-app/>
HUE_REDIRECT_URI=<OAuth2 callback URL, e.g., https://yourdomain.com/api/hue/callback>

# Hydra (MCP OAuth) — see docker-compose.yml
HYDRA_ISSUER_URL=<public Hydra issuer, e.g. https://yourdomain.com or http://localhost:4444>
HYDRA_ADMIN_URL=http://hydra:4445
HYDRA_SECRETS_SYSTEM=<Hydra cookie/token encryption secret>
APP_PUBLIC_URL=<base URL Hydra redirects login/consent to>
```

Hue OAuth tokens (access/refresh/username) are obtained at runtime and persisted via the
`TokenStore` (not written back to `.env`).

## API Endpoints

### Authentication
- `POST /api/auth` - Verify password; on success returns a signed session JWT. Protected `/api/*` endpoints require `Authorization: Bearer <jwt>`.

### OAuth2 (Bridge Authorization)
- `GET /api/hue/authorize` - Start OAuth2 flow (returns authorization URL)
- `GET /api/hue/callback` - OAuth2 callback (handles token exchange)
- `POST /api/hue/link` - Complete bridge linking after OAuth

### Status & Lamps
- `GET /api/status` - Connection status + automation state
- `GET /api/lamps` - List all lamps
- `GET /api/lamps/{id}` - Get single lamp
- `PUT /api/lamps/{id}` - Update lamp (auth required)
- `PUT /api/lamps/all` - Update all lamps at once (auth required) - use case: "I left home"/"I am back" control
- `DELETE /api/lamps/{id}/override` - Clear manual override

### Groups
- `GET /api/groups` - List all groups

### Sensors
- `GET /api/sensors` - List all sensors (switches, smart buttons, motion, etc.). Used by the smart-button picker UI.

### Automation
- `GET /api/automation` - Get automation status
- `POST /api/wakeup` - Wake action ("Lamps on")
- `POST /api/sleep` - Sleep action ("Lamps off")

### Settings
- `GET /api/settings` - Get current settings (pseudo-sunset time, location, excluded lamp IDs, automation mode colors)
- `PUT /api/settings` - Update settings (auth required)

**Excluded lamps:** Automation now uses an opt-out model. All lamps known to the bridge are automated by default. The `excludedLampIds` field in `SettingsResponse`/`SettingsUpdateRequest` lists lamps the user has excluded (e.g. controlled by a motion sensor in the official Hue app). Configurable via the "Lamps" dialog on the main screen.

**Smart-button toggle:** `GET /api/sensors` returns all bridge sensors (motion, switches, smart buttons, daylight, temperature). The `toggleButtonSensorId` settings field designates one `ZLLSwitch`/`ZGPSwitch` to act as a wake/sleep toggle. `LampStateCache` polls `/sensors` every 5s and notifies `AutomationManager.onSensorsRefreshed`; when the configured sensor's `state.lastupdated` changes, the manager calls `wakeUp()`/`goToSleep()` based on current `userState`. Configurable via the "Button" dialog on the main screen.

### Real-time Sync
- `GET /api/sync` - Lightweight sync state (no Hue API calls) - returns automation state, pending operations, overrides
- `POST /api/sync/pending` - Mark lamps as pending (before starting operation)
- `DELETE /api/sync/pending` - Clear pending lamps (after operation completes)

### MCP (Model Context Protocol)
- `/mcp` - MCP SSE endpoint (served by the `quarkus-mcp-server-sse` extension; bearer-token protected via `quarkus-oidc`)
- OAuth discovery / token / DCR endpoints are provided by **Hydra** fronted by `quarkus-oidc-proxy` (under the app domain, e.g. `/q/oidc/...`) — no longer hand-rolled.
- `GET /login`, `GET/POST /consent` - login/consent provider Hydra delegates to (`auth/HydraLoginConsentResource.kt`), reusing the app password.

The hand-rolled `/mcp/authorize`, `/mcp/token`, `/mcp/register`, and `.well-known` endpoints were removed in the Quarkus migration.

## MCP (Model Context Protocol)

The server exposes an MCP endpoint for integration with MCP clients via HTTP OAuth.

**Connection:** Add `https://<domain>/mcp` as a connector URL in your MCP client (Claude Desktop, Claude Code, etc.).
**Important:** The MCP base path is `/mcp` (not `/api/mcp`). Using `/api/mcp` will result in errors.

**Authentication:** OAuth 2.1 (Authorization Code + PKCE), with Ory Hydra as the authorization
server and `quarkus-oidc` validating the resulting bearer JWT on `/mcp`. Hydra delegates
login/consent to the app, which prompts for the Hue Manager password.

**Implementation:**
- Uses the **`quarkus-mcp-server-sse`** extension (`io.quarkiverse.mcp`, 1.13.0) — tools/resources
  are declared with `@Tool` / `@Resource` annotations; the extension provides the SSE transport.
- Server file: `server/.../mcp/McpServer.kt`
- Note: the extension rejects `suspend` `@Tool` methods, so the async tools wrap their suspend
  service calls in `runBlocking`.

**Available Resources:**
| Resource URI | Description |
|--------------|-------------|
| `hue://lamps` | List all lamps with current state (on/off, brightness, color, automation status) |

**Available Tools:**
| Tool | Description |
|------|-------------|
| `get_lamp_state` | Get detailed state of a specific lamp including automation/override/Hue Sync status |
| `set_lamp_state` | Control a lamp (on/off, brightness, hue, saturation, color temperature). Creates 1-hour override. |
| `set_all_lamps` | Control all lamps at once (on/off, brightness, hue, saturation, color temperature). Creates 1-hour overrides for all lamps. |
| `clear_lamp_override` | Clear manual override for a lamp, returning it to automation control |
| `get_automation_status` | Get current automation mode, user state, target color, and overridden lamps |
| `wake_up` | Trigger wake action ("Lamps on") - starts daylight automation sequence |
| `go_to_sleep` | Trigger "Lamps off" action - clears all manual overrides and turns off all lamps. Use when going to sleep or leaving home. |

**UI Integration:**
- Main screen includes "MCP" button that opens a dialog with the connector URL
- One-click copy button copies the URL to the clipboard
- Platform-specific clipboard implementation (JVM, WasmJS, Android)

## In-Memory Lamp State Cache

The server maintains an in-memory cache of all lamp and group state from the Philips Hue Cloud API. All read operations (API endpoints, MCP tools, automation sync checks) return instantly from cached data with zero API calls.

**Implementation:** `LampStateCache` class in `server/.../hue/LampStateCache.kt`

**How it works:**
- **Startup:** Cache is populated once via bulk `getLights()` + `getGroups()` calls
- **Background refresh:** Every 5 seconds, the cache is refreshed from Philips Cloud (2 API calls total: 1 `getLights` + 1 `getGroups`)
- **Optimistic updates:** After successful write operations (`setLightState`), the cached state is immediately patched so subsequent reads reflect the change without waiting for the next refresh
- **Force refresh:** After `wakeUp()`, `goToSleep()`, or bridge linking, the cache is immediately refreshed

**Thread safety:** `@Volatile` references to immutable `Map` snapshots. Reads are lock-free. Writes create new map copies.

**Impact:**
- `/api/sync` is truly instant (was: N+1 API calls per poll, where N = number of lamps)
- `/api/lamps` is instant (was: 2 API calls)
- MCP tools read from cache (was: multiple API calls per tool)
- Background refresh: 2 API calls every 5s, regardless of number of clients (was: ~22 calls/sec with 10 lamps)

## Persistent Settings (SQLite)

Runtime-mutable settings survive server restarts via a SQLite database. Secrets and bootstrap
config (password hash, JWT secret, Hue OAuth client id/secret/tokens, region, timezone) stay in
`.env`; only the settings that the user changes at runtime live in the DB.

**Implementation:** `server/.../persistence/SettingsStore.kt` — a tiny key/value store
(`app_state(key TEXT PRIMARY KEY, value TEXT)`, WAL mode, UPSERT) over JDBC; SQLite native
support comes from `quarkus-jdbc-sqlite` (Agroal datasource configured in `application.properties`).
DB path from `DATABASE_PATH` (default `data/hue.db`; `/app/data/hue.db` in Docker).

**Persisted keys** (written by `AutomationManager`):
- `user_state` — the on/off button state (`AWAKE`/`ASLEEP`)
- `pseudo_sunset`, `night_time` — schedule times
- `daylight_color`, `evening_color`, `night_color` — JSON `ModeColorConfig`
- `excluded_lamp_ids` — JSON array
- `toggle_button_sensor_id` — smart-button sensor id

**Lifecycle:**
- `AutomationManager` takes an optional `SettingsStore` (null = no persistence, used by tests).
  Its `init` block loads persisted values, overriding the `.env`-derived defaults.
- Every settings setter and `wakeUp()`/`goToSleep()` writes through to the store immediately.
- `resumeFromPersistedState()` (called from `Startup.kt` on the Quarkus `StartupEvent`, after the
  lamp cache initializes) re-applies automation + restarts the heartbeat if the persisted
  `user_state` was `AWAKE`.
- **Not** persisted (ephemeral by design): 1-hour lamp overrides, `wakeUpTime`, reachability/
  power-state tracking.

**Docker:** named volume `hue-data:/app/data` (in `docker-compose.yml`). The `Dockerfile` pre-creates
`/app/data` owned by the non-root runtime user (uid 1001) so the volume inherits write permission.

## Rate Limiting

Philips Hue Remote API has rate limits:
- Light operations: 10 requests/second (token bucket)
- Group operations: 1 request/second (stricter limit)

**Implementation:**
- Token bucket algorithm with monotonic time to avoid clock drift
- `lightRateLimiter`: 10 tokens refilling every 1 second
- `groupRateLimiter`: 1 token refilling every 1 second
- Precise wait time calculation: waits exactly the minimum time needed for next token
- Only updates time mark when tokens are actually added (prevents time drift)
- All rate limiters are coroutine-safe using Kotlin's `Mutex`

**Applied to:**
- All `/lights` API calls (getLights, getLight, setLightState)
- All `/groups` API calls (getGroups)
- Background cache refresh (getLights + getGroups every 5s)
- OAuth2 and bridge linking calls are not rate-limited (one-time setup operations)

## Daylight Simulation Logic

The automation uses sunrise/sunset times calculated from the configured location (REGION in .env). Simple on/off logic: lamps on when it's dark, off when the sun is up.

**Daily Flow Example (Berlin, January):**
```
06:00 - User presses "Lamps on", sun not risen yet → AUTO_COMPENSATION (100% warm white)
08:15 - Sunrise → lamps turn off
16:30 - Sunset → lamps turn on (100% warm white)
21:05 - Pseudo-sunset → EVENING (bright orange #FF5500, 100%)
00:05 - Pseudo-sunset+3h → NIGHT (dim orange #FF5500, 1%)
sleep_action: Turn off all automated lamps
```

**Automation Modes:**
- `AUTO_COMPENSATION` - Simple sun-based on/off:
  - Sun is down (before sunrise / after sunset): 100% warm white (CT 350 / ~2850K)
  - Sun is up (between sunrise and sunset): lamps off
- `EVENING` - From pseudo-sunset to +3h: bright orange light (#FF5500, 100%)
- `NIGHT` - After pseudo-sunset+3h until sunrise: dim orange light (#FF5500, 1%)
- `USER_ASLEEP` - User pressed "Lamps off", lamps off

**Sun Calculation:**
- Uses NOAA Solar Calculator algorithm
- Calculates sunrise, sunset, and solar noon based on latitude/longitude from REGION config
- Handles polar regions gracefully (falls back to 6 AM as sunrise if sun doesn't rise/set)

**Out-of-sync Detection:**
- System detects when lamp state differs from automation target (e.g., changed by other apps)
- "Clear override" button appears for both manual overrides AND lamps changed externally
- Uses tolerance-based comparison: ~10% for brightness, ~5% for hue/saturation, ~30 Mirek for color temperature
- Allows users to restore automation control even when lamps are changed via Google Home, Alexa, or official Hue app

## Hue Sync Entertainment Mode

When Hue Sync entertainment areas are active:
- Server detects active entertainment groups and marks lamps with `inEntertainment` flag
- UI shows "Controlled by Hue Sync" status for entertainment-active lamps
- Manual controls are completely hidden for entertainment lamps:
  - Color indicator circle (status dot) is hidden
  - On/off toggle switch is hidden
  - Brightness slider is hidden
  - Color picker is hidden
  - Override clear button is disabled
- Only lamp name and "Controlled by Hue Sync" status text remain visible
- Allows seamless use of Hue Sync app alongside automation without conflicts

## File Structure

Package directories are **flattened**: sources live directly under each source set's `kotlin/`
root (no `io/github/commandertvis/huemanager/` prefix dirs). Package declarations are unchanged
(`io.github.commandertvis.huemanager.*`); Kotlin allows directory ≠ package.

```
hue-manager/
├── server/src/main/kotlin/                 # base package io.github.commandertvis.huemanager
│   ├── AuthResource.kt      # POST /api/auth → issues session JWT
│   ├── PasswordAuthService.kt
│   ├── SpaResource.kt       # serves the Wasm SPA from runtime web/ dir
│   ├── Startup.kt           # @Observes StartupEvent: init Hue + cache + resume automation
│   ├── auth/                # JwtService, PasswordAuthenticator, HydraLoginConsentResource, HydraAdminClient
│   ├── rest/                # ApiResource, HueOAuthResource, RestSupport (AuthVerifier)
│   ├── mcp/                 # McpServer.kt (quarkus-mcp-server @Tool/@Resource)
│   ├── automation/          # Daylight automation (AutomationManager, SunCalculator)
│   ├── config/              # AppConfig (@ConfigMapping), Config
│   ├── hue/                 # HueApi (REST client), HueRemoteClient, HueService, LampStateCache, RateLimiter, HueModels
│   └── persistence/         # SettingsStore
│   src/main/resources/application.properties   # Quarkus config (http, datasource, oidc, oidc-proxy)
├── shared/src/commonMain/kotlin/            # models/, api/, network/, Platform.kt, Constants.kt
├── composeApp/src/commonMain/kotlin/        # App.kt, ui/, viewmodel/, network/, auth/ (AuthStorage), storage/
├── composeApp/src/{jvmMain,wasmJsMain,androidMain}/  # Platform-specific implementations
├── androidApp/              # Android entry point
├── gradle/libs.versions.toml  # Gradle version catalog
├── .env.example             # Environment configuration template
├── Dockerfile               # Multi-stage: GraalVM native build → ubi9-minimal runtime (used by compose + CI)
├── docker-compose.yml       # hue-manager + hydra + hydra-migrate (+ optional Caddy)
├── .github/workflows/docker-publish.yml  # CI/CD pipeline (buildx native image → GHCR)
├── .github/workflows/release-desktop.yml # CI/CD pipeline (DMG + Homebrew Cask)
├── Caddyfile.example        # Caddy reverse proxy config (HTTPS)
├── TASK.md                  # Human-written design document
├── AGENTS.md                # Simplified context for AI sub-agents
├── CLAUDE.md                # This file - AI memory/context
└── CLAUDE.local.md          # Machine-local (gitignored) Kotlin code-intelligence policy
```

## Key Server Files

- `server/.../Startup.kt` - Quarkus `StartupEvent` observer: initializes Hue + lamp cache, resumes automation (replaces the old `Application.kt` `main()`)
- `server/.../AuthResource.kt` - `POST /api/auth`: verifies password (`PasswordAuthService`), returns a session JWT
- `server/.../rest/ApiResource.kt` - JAX-RS REST API (lamps, groups, sensors, automation, settings, sync)
- `server/.../rest/HueOAuthResource.kt` - Philips Hue bridge OAuth (`/api/hue/authorize|callback|link`)
- `server/.../rest/RestSupport.kt` - `AuthVerifier.requireAuth()` (session-JWT bearer check) + helpers
- `server/.../auth/JwtService.kt` - jose4j HS256 sign/verify of SPA session tokens
- `server/.../auth/HydraLoginConsentResource.kt` / `HydraAdminClient.kt` - Hydra login/consent provider for MCP OAuth
- `server/.../SpaResource.kt` - serves the Compose/Wasm SPA dist from the runtime `web/` dir
- `server/.../config/AppConfig.kt` - `@ConfigMapping(prefix="hue")` typed config; `Config.kt` helpers
- `server/.../hue/HueModels.kt` - Serializable DTOs for the Hue v1 REST API (lights, groups, sensors)
- `server/.../hue/HueApi.kt` / `HueRemoteClient.kt` - Quarkus REST client + service for the Hue Remote API (OAuth2)
- `server/.../hue/HueService.kt` - Service layer managing Hue connection via Remote API
- `server/.../hue/LampStateCache.kt` - In-memory cache of lamp/group state with background refresh
- `server/.../hue/RateLimiter.kt` - Token bucket and minimum delay rate limiters
- `server/.../persistence/SettingsStore.kt` - SQLite key/value store for persistent runtime settings
- `server/.../automation/AutomationManager.kt` - User state, lamp overrides, heartbeat, automation mode calculation; loads/persists settings via `SettingsStore`
- `server/.../automation/SunCalculator.kt` - NOAA Solar Calculator algorithm for sunrise/sunset/solar noon calculation
- `server/.../mcp/McpServer.kt` - MCP `@Tool`/`@Resource` implementations (quarkus-mcp-server)

## Key Shared Files

- `shared/.../models/Lamp.kt` - Lamp, LampState, ColorMode, LampType
- `shared/.../models/Group.kt` - Group, GroupType, RoomClass
- `shared/.../models/Automation.kt` - AutomationState, UserState, LampOverride, SunTimes
- `shared/.../models/Auth.kt` - AuthRequest, AuthResponse
- `shared/.../api/ApiModels.kt` - API DTOs (StatusResponse, LampsResponse, etc.)
- `shared/.../network/JsonConfig.kt` - Shared JSON serialization configuration
- `shared/.../Platform.kt` - Platform-specific utilities (expect/actual pattern)
- `shared/.../Constants.kt` - Shared constants

## Key UI Files

- `composeApp/.../network/ApiClient.kt` - Multiplatform Ktor client with all API methods
- `composeApp/.../network/RateLimiter.kt` - Client-side rate limiting
- `composeApp/.../auth/AuthStorage.kt` - Persistent session-JWT storage with StateFlow (delegates to PlatformStorage); the password is never persisted
- `composeApp/.../storage/PlatformStorage.kt` - Platform-specific persistent storage interface (server URL + auth token)
- `composeApp/.../viewmodel/AuthViewModel.kt` - Login state management
- `composeApp/.../viewmodel/LampsViewModel.kt` - Lamp state and control management
- `composeApp/.../viewmodel/ServerConnectViewModel.kt` - Server URL validation
- `composeApp/.../ui/LoginScreen.kt` - Password input with error handling
- `composeApp/.../ui/MainScreen.kt` - Full lamp control interface with automation state display
- `composeApp/.../ui/LampCard.kt` - Compact lamp card with colored left border, inline brightness slider, expandable color picker
- `composeApp/.../ui/ServerConnectScreen.kt` - Server URL input and validation
- `composeApp/.../ui/PleaseAuthorizeScreen.kt` - OAuth2 authorization instructions
- `composeApp/.../ui/Theme.kt` - Light/dark theme based on system preference
- `composeApp/.../ui/McpOAuthScreen.kt` - MCP OAuth password form (WASM-rendered)

## Production Deployment

A single multi-stage **`Dockerfile`** is used for both local `docker compose up --build` and CI:
- **Build stage** (`ghcr.io/graalvm/graalvm-community:21`): builds the Wasm SPA
  (`:composeApp:wasmJsBrowserDistribution`) and the native server
  (`:server:quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false`).
- **Runtime stage** (`ubi9-minimal`, glibc): ships the native binary + `web/` dir, runs as a
  non-root user, healthcheck via `curl`. (`Dockerfile.runtime` was removed.)

CI (`.github/workflows/docker-publish.yml`) builds this Dockerfile via Docker **buildx** with GHA
layer cache and pushes to GHCR (`ghcr.io/commandertvis/hue-manager:sha-...` + `latest`). The
native compile is memory/CPU-heavy (several minutes).

**Production setup:**
1. Clone repo to server; `cp .env.example .env` and configure (incl. `HYDRA_*`, `HUE_JWT_SECRET`).
2. `cp Caddyfile.example Caddyfile` and set domain.
3. `docker compose up -d` — starts `hue-manager` (native), `hydra` + `hydra-migrate`, and (uncomment) Caddy.

**docker-compose.yml services:** `hue-manager` (built from `Dockerfile` or pulled from GHCR),
`hydra` + `hydra-migrate` (Ory Hydra OAuth2 AS, SQLite-backed), optional `caddy` for HTTPS.
Named volumes: `hue-data` (settings DB), `hydra-data` (Hydra DB).

## Desktop App Distribution

The desktop (macOS) app is built as a DMG and published via GitHub Actions (`release-desktop.yml`):

- On push to `master`, builds DMG, computes SHA256, uploads as "nightly" GitHub Release
- Homebrew Cask formula lives on orphan `brew` branch (`Casks/hue-manager.rb`), auto-updated by CI
- Install: `brew install --cask commandertvis/hue-manager/hue-manager`

**Branch structure:**
- `master` — main development branch (code + CI)
- `brew` — orphan branch containing only the Homebrew Cask formula (auto-updated by CI)

## Real-time State Synchronization

The app implements Google Docs-style real-time synchronization across multiple clients:

**Dual Polling Strategy:**
- **Fast poll (500ms):** Queries `/api/sync` for automation state and pending operations (lightweight, no Hue API calls)
- **Slow poll (10s):** Queries `/api/lamps` to get actual lamp states from Hue bridge (respects rate limits)

**Pending Operations:**
- When any client starts a lamp operation, server marks those lamps as "pending"
- All connected clients see pending lamps and gray out their controls
- When operation completes, server removes pending state
- Pending states auto-expire after 5 seconds as a safety net

**UI Behavior:**
- Lamp cards gray out (50% opacity) when pending - whether from local operation or another client
- Controls are disabled for pending lamps
- No manual "Refresh" button needed - state updates automatically
- Changes propagate to all clients within 500ms

**Implementation:**
- Server: `AutomationManager` tracks `pendingOperations` map with timestamps
- Server: `/api/sync` returns `pendingLampIds` list along with automation state
- Client: `LampsViewModel` runs two coroutine jobs for fast/slow polling
- Client: UI uses unified `pendingLampIds` from server for all loading states (no separate local tracking)

## UI Features

**Main Screen:**
- Compact lamp cards with colored left border indicating lamp color/state
- Inline brightness slider in the same row as lamp name (no separate row)
- Automation state display ("Daylight mode", "Evening light") with target color indicator
- Per-lamp controls: on/off toggle, inline brightness slider, expandable RGB color picker with hex input
- Per-lamp loading state (controls gray out during API calls or pending from other clients)
- Real-time sync - no manual refresh needed
- "Clear override" button for manual overrides or out-of-sync lamps
- Prominent "Lamps on/off" button (primary color for on, red for off) in status bar
- "MCP" button for Claude integration setup
- Build commit hash displayed in bottom-right corner (generated at build time via Gradle task)

**Color Picker:**
- In-house HSV implementation (based on colorpicker-compose, customized and trimmed)
- Immediate color application (no "Set" button)
- Hex input validates characters (0-9, a-f only) and limits to 6 characters
- Preview square reflects changes from hex input and picker

**Error Handling:**
- 401 errors show "Incorrect password" instead of generic error
- Platform-specific URL opening for OAuth flow

## Recent Changes

**June 2026 — Quarkus + GraalVM native migration:**
- Migrated the server from **Ktor + Netty to Quarkus**, compiled to a **GraalVM native image**
  (~86 MB binary, ~45 MB RSS vs ~215 MB on the JVM). CDI beans + JAX-RS resources replaced the
  Ktor route DSL; `Application.kt` removed (Quarkus self-boots; `Startup.kt` runs init).
- **JWT session auth** for the SPA: `POST /api/auth` returns a jose4j HS256 token; the password is
  sent only at login (was: raw password as a bearer on every request). `HUE_JWT_SECRET` env.
- **Dropped the hand-rolled MCP OAuth server**: MCP now uses Ory Hydra (separate container) +
  `quarkus-oidc` (resource server) + `quarkus-oidc-proxy`. Login/consent delegated to the app.
- **MCP** ported from the MCP Kotlin SDK to the `quarkus-mcp-server-sse` extension.
- Hue client → Quarkus REST client; persistence → `quarkus-jdbc-sqlite`; logging → Quarkus/JBoss
  (logback removed); config → `@ConfigMapping(prefix="hue")` (dotenv removed, `HUE_*` env keys).
- **Flattened package directories** (removed the `io/github/commandertvis/huemanager` prefix dirs;
  package declarations unchanged).
- Toolchain bumps: **Gradle 9.6.0, AGP 9.0.0, Kotlin 2.4.0, Quarkus 3.36.3, Compose MP 1.11.1**,
  kotlinx 1.11.0, ktor client 3.5.0, sqlite-jdbc 3.53.2.0. **GraalVM 21** is the sole JDK.
- Docker consolidated to one multi-stage native `Dockerfile` (removed `Dockerfile.runtime`); CI
  builds it via buildx. Config cache enabled (Android APK needs `--no-configuration-cache`).

**June 2026:**
- Added persistent runtime settings via SQLite (`SettingsStore`): on/off `user_state` and all schedule preferences (pseudo-sunset, night time, daylight/evening/night colors, excluded lamps, toggle button) now survive server restarts. Secrets/bootstrap stay in `.env`.
- `AutomationManager` loads persisted settings on construction, writes through on every setter, and `resumeFromPersistedState()` restores AWAKE automation + heartbeat on boot.
- Docker: added `hue-data` named volume at `/app/data`; Dockerfiles pre-create the dir owned by `huemanager`. New `DATABASE_PATH` env var (default `data/hue.db`).
- Added `org.xerial:sqlite-jdbc` dependency.

**March 2026:**
- Redesigned lamp cards: compact layout with colored left border, inline brightness slider, reduced vertical spacing
- Made "Lamps on/off" button prominent with color-coded styling (primary/red)
- Added build commit hash display in bottom-right corner of main screen (generated via Gradle task at build time)
- Added `BuildInfo.kt` generation in composeApp build (git short hash → `BUILD_COMMIT` constant)
- Added auto light/dark theme based on system preference (`HueManagerTheme` using `isSystemInDarkTheme()`)
- Renamed UI label "Auto-compensating" → "Daylight mode"
- Simplified auto-compensation logic: sun up = lamps off, sun down = warm white
- Branch rename: `dev` → `master`, `master` → `brew` (orphan, Cask-only)
- Updated dependencies: Kotlin 2.3.10, Ktor 3.4.1, Compose Multiplatform 1.10.2, kotlinx-serialization 1.10.0, MCP SDK 0.9.0

**February 2026:**
- Added `LampStateCache` for in-memory lamp/group state with background refresh every 5s -- all read endpoints now return instantly from cache (55x reduction in Philips Cloud API calls)
- Fixed lamp color display bug: `getLampColor()` now checks `colorMode` first instead of always using stale hue/saturation values when lamp is in CT mode
- Added automatic discovery of new Hue lamps (cache detects new lamps on refresh)
- Enabled Gradle parallel builds for faster compilation
- Added lamp power state tracking (`LampPowerState`) to detect recently turned-on lamps
- Implemented 5-second grace period to avoid false override detection after lamp power-on
- Major refactoring of `AutomationManager`: extracted helper methods, grouped constants, improved code organization
- Fixed Philips Hue OAuth token exchange failing due to unknown 'scope' field (added `ignoreUnknownKeys=true` to JSON config)
- Fixed `needsReauthorization` flag not being cleared after successful token exchange
- Reverted MCP OAuth skip-password feature (now requires password entry for all authorizations)

**Late January 2026:**
- Added robust OAuth re-authorization: when Hue token refresh fails, `needsReauthorization` flag is set and UI prompts user to re-authorize
- Removed redundant "All Lamps" switch from MainScreen (functionality covered by toggle button)
- Renamed "I woke up!"/"I'm asleep!" buttons to "Lamps on/off" toggle button in MainScreen
- Implemented unreachable lamp handling with `LampReachabilityState` tracking
- Lamps transitioning from unreachable → reachable get automation applied immediately (no 1-hour grace period)
- Skip unreachable lamps in out-of-sync detection
- Hide override indicator for unreachable lamps
- Instant state refresh after wakeUp/goToSleep actions (`pollSync()` and `pollLamps()` called immediately)
- Unified pending state tracking: removed separate `loadingLampIds` and `isGlobalToggling`, use only `pendingLampIds`
- Hide "Change server" link in WASM app loading state
- Changed default docker-compose.yml to use published GHCR image

**January 2026:**
- Implemented real-time state synchronization across clients (Google Docs-style)
- Added `/api/sync` endpoint for lightweight polling (no Hue API calls)
- Added pending operations tracking for cross-client coordination
- Dual polling strategy: 500ms for sync state, 10s for lamp states
- Removed manual "Refresh" button from UI
- Reverted MCP to SSE transport (`SseServerTransport`) and simplified Claude Desktop setup
- Bumped MCP Kotlin SDK from 0.8.1 to 0.8.3
- Added SunCalculator for dynamic sunrise/sunset calculations based on REGION config
- Replaced deprecated `kotlinx.datetime.Instant/Clock` with `kotlin.time` equivalents
- Enhanced MCP OAuth handling
- Temporarily switched MCP from SSE to Streamable HTTP transport for Claude Code compatibility, then reverted back to SSE
- Added color support (hue, saturation, color_temperature) to `set_all_lamps` MCP tool
- Fixed MCP OAuth authorize page: replaced SPA with HTML form
- Fixed route order: MCP routes now registered before SPA catch-all
- Excluded `/mcp`, `/api`, `/.well-known` from SPA routing
- Improved WASM app to detect `/mcp/authorize` path for OAuth UI
- Simplified automation mode handling and fixed evening transition brightness
- Removed unused `initialServerUrl` parameter from App function
- Updated README with MCP resources and tool descriptions
- Removed separate colorpicker module, integrated components into composeApp
- Streamlined resource cleanup and enhanced code readability
- Enhanced URL handling and validation in ServerUrlStorage and ApiClient
