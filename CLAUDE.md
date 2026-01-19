# Hue Manager - Project Memory

## Project Overview

A Philips Hue lamp management system with:
- **server/**: Ktor backend for managing lamp state via Philips Hue Remote API (OAuth2)
- **composeApp/**: Compose Multiplatform UI library (Desktop JVM, Web JS/WasmJS, Android target)
- **androidApp/**: Android application module (depends on composeApp)
- **shared/**: Shared data models and constants

**Deployment:** Self-hosted on VDS with Docker + Caddy reverse proxy for HTTPS.

**IMPORTANT:** Philips Hue OAuth2 requires HTTPS and a valid domain name. The redirect URI must be publicly accessible over HTTPS.

## Current State

**Implemented:**
- Basic project structure with Gradle Kotlin DSL
- Multi-target configuration (Android, JVM, JS, WasmJS)
- **Phase 1 - Core Infrastructure (COMPLETE):**
  - `.env.example` with configuration structure
  - Environment loading with dotenv-kotlin
  - Ktor HTTP client for Hue Remote API
  - OAuth2 authentication with Philips Hue (configurable redirect URI)
  - Credential storage (updates .env automatically)
  - Basic API endpoints: `/api/status`, `/api/lamps`, `/api/groups`
  - OAuth2 authorization flow with detailed logging and parameter validation
  - Platform-specific URL opening for authorization flow

**Server Files:**
- `server/.../config/Config.kt` - Configuration loading from .env
- `server/.../hue/HueClient.kt` - HTTP client for local Hue REST API (legacy, kept for reference)
- `server/.../hue/HueRemoteClient.kt` - HTTP client for Philips Hue Remote API (OAuth2)
- `server/.../hue/HueService.kt` - Service layer managing Hue connection via Remote API
- `server/.../hue/RateLimiter.kt` - Token bucket and minimum delay rate limiters

**Shared Files:**
- `shared/.../models/Lamp.kt` - Lamp, LampState, ColorMode, LampType
- `shared/.../models/Group.kt` - Group, GroupType, RoomClass
- `shared/.../models/Automation.kt` - AutomationState, UserState, LampOverride, SunTimes
- `shared/.../models/Session.kt` - Session, LoginRequest, LoginResponse
- `shared/.../api/ApiModels.kt` - API DTOs (StatusResponse, LampsResponse, etc.)
- `shared/.../network/JsonConfig.kt` - Shared JSON serialization configuration
- `shared/.../Platform.kt` - Platform-specific utilities (expect/actual pattern)
- `shared/.../Constants.kt` - Shared constants

**Server API & Auth:**
- `server/.../auth/SessionManager.kt` - Token-based session management
- `server/.../automation/AutomationManager.kt` - User state, lamp overrides, heartbeat, automation mode calculation

**UI Files:**
- `composeApp/.../network/ApiClient.kt` - Multiplatform Ktor client with all API methods (platform-specific implementations in jvmMain, wasmJsMain)
- `composeApp/.../network/RateLimiter.kt` - Client-side rate limiting
- `composeApp/.../auth/SessionStorage.kt` - Persistent session token storage with StateFlow (platform-specific implementations)
- `composeApp/.../storage/ServerUrlStorage.kt` - Platform-specific server URL storage (JVM, WasmJS implementations)
- `composeApp/.../viewmodel/AuthViewModel.kt` - Login state management
- `composeApp/.../viewmodel/LampsViewModel.kt` - Lamp state and control management
- `composeApp/.../viewmodel/ServerConnectViewModel.kt` - Server URL validation
- `composeApp/.../ui/LoginScreen.kt` - Password input with error handling
- `composeApp/.../ui/MainScreen.kt` - Full lamp control interface with automation state display
- `composeApp/.../ui/LampCard.kt` - Individual lamp card with toggle, brightness slider, RGB color picker with hex input
- `composeApp/.../ui/ServerConnectScreen.kt` - Server URL input and validation
- `composeApp/.../ui/PleaseAuthorizeScreen.kt` - OAuth2 authorization instructions

**Tech Stack:**
- Kotlin 2.3.0
- Ktor 3.3.3 (server + client)
- Compose Multiplatform 1.10.0
- kotlinx-serialization 1.9.0
- kotlinx-datetime 0.7.1-0.6.x-compat
- kotlinx-coroutines 1.10.2
- dotenv-kotlin 6.5.1
- Gradle with version catalog

## Architecture

```
Client (composeApp) <--HTTP--> Server (Ktor on VDS) <--OAuth2/REST--> Philips Cloud (api.meethue.com) <--> Hue Bridge
```

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
8. Server handles callback, exchanges code for tokens, and stores them in `.env`
9. User returns to the app and clicks "Check Again" to verify connection

**IMPORTANT OAuth2 Requirements:**
- **HTTPS is mandatory** - Philips Hue OAuth2 will not work with HTTP-only redirect URIs
- **Valid domain required** - Cannot use IP addresses or localhost for production
- **Publicly accessible** - The redirect URI must be reachable from Philips Cloud servers
- Use Caddy or similar reverse proxy to provide HTTPS with automatic Let's Encrypt certificates

**Note:** Recent fixes ensure proper OAuth2 parameter naming (client_id) and configurable redirect URI support.

The server acts as a persistent process that:
1. Maintains connection to Hue bridge via Philips Cloud
2. Runs automation (daylight simulation)
3. Provides API for the client UI
4. Handles 10-minute heartbeat for automation persistence

## Environment Variables (.env)

```bash
# Authentication
PASSWORD=<auth password>

# Location for sunrise/sunset calculation
REGION=<latitude,longitude>
PSEUDO_SUNSET=21:05
TIMEZONE=Europe/Berlin

# Philips Hue Remote API (OAuth2) - REQUIRED
HUE_CLIENT_ID=<from developers.meethue.com/add-new-hue-remote-api-app/>
HUE_CLIENT_SECRET=<from developers.meethue.com/add-new-hue-remote-api-app/>
HUE_APP_ID=<from developers.meethue.com/add-new-hue-remote-api-app/>
HUE_REDIRECT_URI=<OAuth2 callback URL, e.g., https://yourdomain.com/api/hue/callback>

# OAuth2 tokens (populated automatically after authorization)
HUE_ACCESS_TOKEN=
HUE_REFRESH_TOKEN=
HUE_USERNAME=

# Optional
KEYSTORE_PASSWORD=<for HTTPS>
```

## API Endpoints

### Authentication
- `POST /api/session` - Login with password

### OAuth2 (Bridge Authorization)
- `GET /api/hue/authorize` - Start OAuth2 flow (returns authorization URL)
- `GET /api/hue/callback` - OAuth2 callback (handles token exchange)
- `POST /api/hue/link` - Complete bridge linking after OAuth

### Status & Lamps
- `GET /api/status` - Connection status + automation state
- `GET /api/lamps` - List all lamps
- `GET /api/lamps/{id}` - Get single lamp
- `PUT /api/lamps/{id}` - Update lamp (auth required)
- `PUT /api/lamps/all` - Update all lamps (auth required)
- `DELETE /api/lamps/{id}/override` - Clear manual override

### Groups
- `GET /api/groups` - List all groups

### Automation
- `GET /api/automation` - Get automation status
- `POST /api/wakeup` - "I woke up!" action
- `POST /api/sleep` - "I'm asleep!" action

### Settings
- `GET /api/settings` - Get current settings
- `PUT /api/settings` - Update settings

## Rate Limiting

Philips Hue Remote API has rate limits:
- Light operations: 10 requests/second (token bucket)
- Group operations: 1 request/second (stricter limit)

All rate limiters are coroutine-safe using Kotlin's `Mutex`.

## Daylight Simulation Logic

```
wake_time -> pseudo_sunset: Compensate for sun (bright when dark outside)
pseudo_sunset -> pseudo_sunset+3h: Transition from white to orange (#FF5500), gradually dim
pseudo_sunset+3h onwards: Minimal brightness, orange light only
sleep_action: Turn off all automated lamps
```

## File Structure

```
hue-manager/
├── server/src/main/kotlin/io/github/commandertvis/huemanager/
│   ├── Application.kt       # Entry point + routes
│   ├── auth/                # Session management
│   ├── automation/          # Daylight automation
│   ├── config/              # Environment configuration
│   └── hue/                 # Hue API clients (Remote + Local)
├── shared/src/commonMain/kotlin/io/github/commandertvis/huemanager/
│   ├── models/              # Data models
│   ├── api/                 # API DTOs
│   ├── network/             # Shared JSON configuration
│   ├── Platform.kt          # Platform utilities (expect/actual)
│   └── Constants.kt         # Shared constants
├── composeApp/src/commonMain/kotlin/io/github/commandertvis/huemanager/
│   ├── App.kt               # Main app entry
│   ├── ui/                  # UI screens
│   ├── viewmodel/           # ViewModels
│   ├── network/             # Server API client + rate limiting
│   ├── auth/                # Session storage
│   └── storage/             # Server URL storage
├── composeApp/src/{jvmMain,wasmJsMain}/  # Platform-specific implementations
├── androidApp/              # Android entry point
├── gradle/libs.versions.toml  # Gradle version catalog
├── .env.example             # Environment configuration template
├── Dockerfile               # Multi-stage build for local docker compose
├── Dockerfile.runtime       # Runtime-only image for CI/CD (uses pre-built artifacts)
├── docker-compose.yml       # Docker deployment config (template)
└── Caddyfile.example        # Caddy reverse proxy config (HTTPS)
```

## Production Deployment

**Two Deployment Methods:**

### Method 1: Local Build (`docker compose up`)
- Uses `Dockerfile` (multi-stage build)
- Builds from source during `docker compose up --build`
- Good for: Local development, testing, small deployments
- Configuration: Edit `docker-compose.yml` to use `build: .`

### Method 2: CI/CD with Pre-built Images (Production)
- Uses `Dockerfile.runtime` (runtime-only)
- GitHub Actions builds artifacts and creates Docker image
- Published to GitHub Container Registry (GHCR)
- Tagged with commit hash (e.g., `ghcr.io/commandertvis/hue-manager:sha-abc1234`)
- Good for: Production deployments, faster deploys, reproducible builds

**Production Setup (Method 2):**
1. Clone repository to server
2. Copy `.env.example` to `.env` and configure
3. Copy `Caddyfile.example` to `Caddyfile` and set domain
4. Update `docker-compose.yml` to use GHCR image
5. Run `docker compose up -d`

**Production docker-compose.yml structure:**
- Uses pre-built GHCR image (not local build)
- Caddy reverse proxy for HTTPS with automatic Let's Encrypt certificates
- Bridge network for service communication
- Volume mounts for `.env` file (both `/.env` for Docker, `./.env` for local)
- Health checks for both services
- Named volumes for Caddy data persistence

**Key differences from template:**
- Template: `build: .` → Production: `image: ghcr.io/commandertvis/hue-manager:sha-<hash>`
- Template: `ports: 8080:8080` → Production: `expose: 8080` (internal only)
- Template: `network_mode: host` (commented) → Production: `networks: hue-network`
- Production adds Caddy service with ports 80/443 exposed

## Recent Changes

**Latest Updates (Jan 2026):**
- **UI Enhancements:**
  - Implemented RGB color picker with hex input in LampCard for precise color control
    - Color changes are immediate (no "Set" button required)
    - Hex input validates characters (only 0-9, a-f, A-F allowed) and limits to 6 characters
    - Typing a valid 6-character hex code immediately applies the color
    - Dragging the color picker immediately updates the lamp color
  - Added automation state display in MainScreen showing current mode ("Auto-compensating", "Evening light")
  - Display automation target color and description in MainScreen status bar
  - Changed "Pseudo-sunset" label to "Evening light" for better user understanding
  - Fixed login error messages: 401 errors now show "Incorrect password" instead of "Login failed: 401"

- **Automation Improvements:**
  - Added `AutomationMode` enum with modes: WAKE_UP_COMPENSATION, DAYLIGHT, EVENING_TRANSITION, NIGHT_MODE, USER_ASLEEP
  - Implemented `getCurrentAutomationMode()` in AutomationManager to determine current automation state
  - Added `getAutomationColor()` to provide color information for UI display
  - Extended API response (`AutomationStatusResponse`) to include automation mode and color data

- **OAuth2 & Authentication:**
  - Fixed OAuth2 parameter naming: corrected `clientid` to `client_id` in Hue authorization flow
  - Added support for configurable `HUE_REDIRECT_URI` in environment variables
  - Implemented `PleaseAuthorizeScreen` for OAuth2 authorization instructions
  - Standardized terminology: "pairing" → "authorization", improved consistency across codebase
  - Enhanced OAuth2 authorization URL generation with detailed logging and parameter validation
  - Added platform-specific URL opening functionality for authorization flow:
    - Desktop (JVM): Opens URLs using `java.awt.Desktop.browse()`
    - Android: Opens URLs using Intent with `ACTION_VIEW`
    - Web: Opens URLs using `window.open()`
  - Fixed Android platform `openUrl()` implementation to properly launch browser

- **Infrastructure:**
  - Updated dependency versions (kotlinx-serialization 1.9.0, kotlinx-datetime 0.7.1, kotlinx-coroutines 1.10.2)
  - Documented HTTPS/domain requirement for OAuth2 across all documentation files
  - Created `Caddyfile.example` for easy HTTPS setup with automatic Let's Encrypt certificates
  - Updated `docker-compose.yml` to include production deployment configuration with Caddy
