# Hue Manager - Project Memory

## Project Overview

A Philips Hue lamp management system with:
- **server/**: Ktor backend for managing lamp state via Philips Hue REST API
- **composeApp/**: Compose Multiplatform UI library (Desktop JVM, Web JS/WasmJS, Android target)
- **androidApp/**: Android application module (depends on composeApp)
- **shared/**: Shared data models and constants

## Current State

**Implemented:**
- Basic project structure with Gradle Kotlin DSL
- Multi-target configuration (Android, JVM, JS, WasmJS)
- **Phase 1 - Core Infrastructure (COMPLETE):**
  - `.env.example` with configuration structure
  - Environment loading with dotenv-kotlin
  - Ktor HTTP client for Hue API
  - Hue bridge auto-discovery via meethue.com
  - Hue bridge linking flow (press button)
  - Credential storage (updates .env automatically)
  - Basic API endpoints: `/api/status`, `/api/lamps`, `/api/groups`

**Server Files Created:**
- `server/.../config/Config.kt` - Configuration loading from .env
- `server/.../hue/HueClient.kt` - HTTP client for Hue REST API with rate limiting
- `server/.../hue/HueBridge.kt` - Bridge discovery and linking with rate limiting
- `server/.../hue/HueService.kt` - Service layer managing Hue connection
- `server/.../hue/RateLimiter.kt` - Token bucket and minimum delay rate limiters

**Shared Models Created (Phase 2):**
- `shared/.../models/Lamp.kt` - Lamp, LampState, ColorMode, LampType
- `shared/.../models/Group.kt` - Group, GroupType, RoomClass
- `shared/.../models/Automation.kt` - AutomationState, UserState, LampOverride, SunTimes
- `shared/.../models/Session.kt` - Session, LoginRequest, LoginResponse
- `shared/.../api/ApiModels.kt` - API DTOs (StatusResponse, LampsResponse, etc.)

**Server API & Auth (Phase 3):**
- `server/.../auth/SessionManager.kt` - Token-based session management
- `server/.../automation/AutomationManager.kt` - User state, lamp overrides, heartbeat

**UI Files Created (Phase 5, 6 & 8):**
- `composeApp/.../network/ApiClient.kt` - Multiplatform Ktor client with all API methods
- `composeApp/.../hue/HueBridgeClient.kt` - Direct HTTP client for local bridge pairing (connects to bridge on home network)
- `composeApp/.../auth/SessionStorage.kt` - Persistent session token storage with StateFlow (platform-specific backends)
- `composeApp/.../storage/ServerUrlStorage.kt` - Platform-specific server URL storage (JVM, Android, JS, WasmJS)
- `composeApp/.../viewmodel/AuthViewModel.kt` - Login state management
- `composeApp/.../viewmodel/LampsViewModel.kt` - Lamp state and control management
- `composeApp/.../viewmodel/ServerConnectViewModel.kt` - Server URL validation and connectivity check
- `composeApp/.../viewmodel/BridgePairingViewModel.kt` - Bridge discovery and LOCAL linking flow (connects directly to bridge)
- `composeApp/.../ui/LoginScreen.kt` - Password input with error handling
- `composeApp/.../ui/MainScreen.kt` - Full lamp control interface
- `composeApp/.../ui/LampCard.kt` - Individual lamp card with toggle, brightness slider
- `composeApp/.../ui/ServerConnectScreen.kt` - Server URL input and validation (Desktop/Android)
- `composeApp/.../ui/BridgePairingScreen.kt` - Bridge discovery and pairing UI (Desktop/Android)
- `composeApp/.../ui/PleasePairScreen.kt` - Bridge pairing prompt for web app

**Tech Stack:**
- Kotlin 2.3.0
- Ktor 3.3.3 (server + client)
- Compose Multiplatform 1.10.0
- kotlinx-serialization 1.8.1
- kotlinx-datetime 0.6.2
- dotenv-kotlin 6.5.0
- Gradle with version catalog

## Architecture

```
Client (composeApp) <--HTTP--> Server (Ktor on VDS) <--REST API--> Philips Hue Bridge (via port forward/VPN)
                     \
                      --HTTP--> Philips Hue Bridge (direct, for pairing only)
```

**Bridge Pairing Flow (Critical):**
Since the server is hosted on a remote VDS and cannot directly access the Hue bridge on the home network:
1. **Client discovers bridges** via discovery.meethue.com (works from anywhere)
2. **Client connects directly** to bridge IP on local network via `HueBridgeClient`
3. User presses physical button on bridge
4. **Client creates user** with bridge (HTTP request to local bridge IP)
5. **Client sends credentials** (bridgeIp + username) to server via `/api/bridge/configure`
6. **Server stores credentials** and uses them to control bridge (bridge must be accessible via port forward/VPN/public IP)

The server acts as a persistent process that:
1. Maintains connection to Hue bridge (using credentials from client)
2. Runs automation (daylight simulation)
3. Provides API for the client UI
4. Handles 10-minute heartbeat for automation persistence

**Important:** The bridge IP sent to the server should be the publicly accessible address (or VPN address), NOT the local 192.168.x.x address.

## TODO - Implementation Phases

### Phase 1: Core Infrastructure - COMPLETE
- [x] Create .env.example with required configuration structure
- [x] Add environment loading to server (dotenv-kotlin)
- [x] Set up Ktor HTTP client for Hue API calls
- [x] Implement Hue bridge discovery (via meethue.com)
- [x] Implement Hue bridge linking (press button flow)
- [x] Store Hue API credentials securely (auto-updates .env)

### Phase 2: Data Models (shared/) - COMPLETE
- [x] Lamp model (id, name, state, brightness, color, reachable)
- [x] Room/Group model
- [x] Automation state model (UserState, LampOverride, SunTimes)
- [x] User session model (LoginRequest, LoginResponse)
- [x] API request/response DTOs

### Phase 3: Server API & Hue Integration - COMPLETE
- [x] GET /api/lamps - list all lamps with current state
- [x] GET /api/lamps/{id} - get single lamp state
- [x] GET /api/groups - list all groups
- [x] GET /api/status - connection status + automation state
- [x] PUT /api/lamps/{id} - update lamp (on/off, brightness, color) with auth
- [x] PUT /api/lamps/all - control all lamps at once with auth
- [x] POST /api/session - authenticate with password
- [x] POST /api/wakeup - "I woke up!" action with auth
- [x] POST /api/sleep - "I'm asleep!" action with auth
- [x] GET /api/automation - get automation status
- [x] GET /api/settings - get current settings
- [x] PUT /api/settings - update pseudo-sunset, automated lamps
- [x] DELETE /api/lamps/{id}/override - clear manual override
- [x] POST /api/bridge/configure - configure bridge with IP and optional username
- [x] POST /api/bridge/link - link bridge using button press flow

### Phase 4: Automation Engine - COMPLETE
- [x] Sunrise/sunset calculation based on region (basic algorithm)
- [x] Daylight simulation algorithm (wake -> sunset -> wind-down)
- [x] Heartbeat coroutine (10 min) for state persistence
- [x] Manual override tracking (1 hour timeout)
- [x] Entertainment area detection (Hue Sync support) - polls entertainment groups for active streaming

### Phase 5: UI - Auth Screen - COMPLETE
- [x] Password input field
- [x] Session token storage (persistent with platform-specific backends: JVM Preferences, Android SharedPreferences, browser localStorage)
- [x] Error handling and feedback
- [x] API client with all endpoints (multiplatform)
- [x] AuthViewModel for login state
- [x] Navigation between login and main screens
- [x] Hue bridge linking UI with discovery and pairing flow (Desktop/Android)
- [x] PleasePairScreen for web app (when bridge not linked)

### Phase 6: UI - Lamps Screen - COMPLETE
- [x] Lamp list with current states (LazyColumn)
- [x] Individual lamp controls (toggle switch, brightness slider)
- [x] Color indicator for lamp state
- [x] "Turn all on/off" buttons
- [x] "I woke up!" / "I'm asleep!" toggle button
- [x] Pseudo-sunset time display
- [x] Manual override indicator with clear button
- [x] LampsViewModel with full state management
- [ ] Color picker - deferred (basic color indicator implemented)

### Phase 7: DevOps - COMPLETE
- [x] Dockerfile for server (multi-stage build with JDK 21)
- [x] docker-compose.yml with environment configuration (host network mode for Hue discovery)
- [x] GitHub Actions workflow for Docker image builds (tagged by commit hash, with layer caching)
- [x] .gitignore updates for security (.env added)
- [ ] HTTPS setup documentation (Let's Encrypt) - optional, use reverse proxy

**Note on GitHub Container Visibility:**
The GitHub Actions workflow pushes images to `ghcr.io`. Container visibility (public/private) must be configured manually in GitHub:
1. After first push, go to repository Settings → Packages
2. Select the package → Package settings → Change visibility to Private
3. This is a one-time manual configuration per repository

### Phase 8: UI - Server URL Selection (Desktop & Android) - COMPLETE
- [x] Server URL input screen for local apps (desktop and android)
- [x] URL validation with connectivity check when entering new URL
- [x] Local storage for server URL (platform-specific persistence: JVM Preferences, Android SharedPreferences, browser localStorage)
- [x] Navigation flow: Server selection -> Auth -> Bridge pairing (if needed) -> Main screen
- [x] Error handling for unreachable servers
- [x] Web app uses hardcoded server URL (same origin)

## Technical Notes

### Philips Hue API
- Base URL: `https://<bridge-ip>/api`
- Authentication: Create user via POST to `/api` while bridge button is pressed
- Lamps: GET/PUT `/api/<username>/lights`
- Groups: GET/PUT `/api/<username>/groups`
- Entertainment: Check `/api/<username>/groups` for `type: "Entertainment"`

### Daylight Simulation Logic
```
wake_time -> pseudo_sunset: Compensate for sun (bright when dark outside)
pseudo_sunset -> pseudo_sunset+3h: Transition from white to orange (#FF5500), gradually dim
pseudo_sunset+3h onwards: Minimal brightness, orange light only
sleep_action: Turn off all automated lamps
```

### Environment Variables (.env)
```
PASSWORD=<auth password>
REGION=<latitude,longitude or city name>
PSEUDO_SUNSET=21:05
TIMEZONE=Europe/Berlin
KEYSTORE_PASSWORD=<optional, for HTTPS>
HUE_BRIDGE_IP=<optional, for manual config>
HUE_USERNAME=<stored after linking>
```

## File Structure Reference

```
hue-manager/
├── server/src/main/kotlin/io/github/commandertvis/huemanager/
│   ├── Application.kt       # Entry point
│   ├── routes/              # API route definitions
│   ├── hue/                 # Hue API client
│   ├── automation/          # Daylight automation
│   └── config/              # Environment and configuration
├── shared/src/commonMain/kotlin/io/github/commandertvis/huemanager/
│   ├── models/              # Data models
│   └── api/                 # API DTOs
├── composeApp/src/commonMain/kotlin/io/github/commandertvis/huemanager/
│   ├── App.kt               # Main app
│   ├── ui/                  # UI screens
│   ├── viewmodel/           # ViewModels
│   └── network/             # Server API client
├── androidApp/src/main/kotlin/io/github/commandertvis/huemanager/
│   └── MainActivity.kt      # Android entry point
├── .env.example
├── Dockerfile
└── docker-compose.yml
```

## Misc

### Rate Limiting
Philips Hue bridge has strict rate limits (shared across all connected apps):
- Individual lights: ~10 commands/second
- Groups: 1 command/second

**Implementation (`RateLimiter.kt`):**
1. **Token Bucket Rate Limiter** - For Hue bridge API calls
   - Light operations: 10 requests/second (allows bursts)
   - Group operations: 1 request/second (stricter limit)
   - Uses monotonic time (`TimeSource.Monotonic`) to avoid clock-change issues
2. **Minimum Delay Rate Limiter** - For infrequent operations
   - Discovery: 5 second minimum delay between calls
   - Linking/validation: 1 second minimum delay between attempts

**Protected Operations:**
- `HueClient` lights: getLights(), getLight(), setLightState() → 10 req/sec
- `HueClient` groups: getGroups(), getGroup(), setGroupState() → 1 req/sec
- `HueBridge`: discoverBridges() (5s delay), createUser() (1s delay), validateConnection() (1s delay)

All rate limiters are coroutine-safe using Kotlin's `Mutex`.

### Persistent Session Storage
The application stores session tokens persistently across app restarts using platform-specific storage backends:

**Implementation:**
- **SessionStorage** - Common interface with StateFlow for reactive updates
- **SessionStorageBackend** - Platform-specific implementations:
  - **JVM**: Java Preferences API (`java.util.prefs.Preferences`)
  - **Android**: SharedPreferences (`hue_manager_prefs`)
  - **JS/WasmJS**: Browser localStorage

**Security Note:**
- Session tokens (not raw passwords) are stored persistently
- Tokens can be revoked server-side and have limited lifetime
- Server URL and session token are stored in the same storage mechanism on each platform

This allows users to stay logged in across app restarts without re-entering credentials, while maintaining security through token-based authentication.

### Bridge Pairing Architecture

**Critical Design Decision:**
The Hue bridge pairing process MUST be performed by the client, not the server, because:
- The server runs on a remote VDS and cannot reach the bridge's local IP (192.168.x.x)
- Only devices on the local network can perform the button-press authentication
- The client (desktop/Android app) is on the same network as the bridge

**Implementation (`HueBridgeClient`):**
1. **Platform-specific HTTP clients** - JVM (CIO), Android (OkHttp), JS/WasmJS (Js engine)
2. **Direct HTTP connection** - Uses `http://<bridge-ip>/api` (not HTTPS, to avoid self-signed cert issues on local network)
3. **Button press flow** - Polls bridge for up to 30 attempts (60 seconds) waiting for button press
4. **Credential handoff** - Sends bridgeIp + username to server via `/api/bridge/configure`

**Server Endpoints:**
- `POST /api/bridge/configure` - Accepts bridgeIp + username from client, stores credentials
- `POST /api/bridge/link` - **DEPRECATED** - Should NOT be used as server cannot reach local bridge IP

**Security Note:**
The bridge IP used for pairing (local network) may differ from the IP the server uses (public/VPN). The client should prompt for both if needed, or the bridge must be accessible from the internet via port forwarding.