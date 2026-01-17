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
- `server/.../hue/HueClient.kt` - HTTP client for Hue REST API
- `server/.../hue/HueBridge.kt` - Bridge discovery and linking
- `server/.../hue/HueService.kt` - Service layer managing Hue connection

**Shared Models Created (Phase 2):**
- `shared/.../models/Lamp.kt` - Lamp, LampState, ColorMode, LampType
- `shared/.../models/Group.kt` - Group, GroupType, RoomClass
- `shared/.../models/Automation.kt` - AutomationState, UserState, LampOverride, SunTimes
- `shared/.../models/Session.kt` - Session, LoginRequest, LoginResponse
- `shared/.../api/ApiModels.kt` - API DTOs (StatusResponse, LampsResponse, etc.)

**Server API & Auth (Phase 3):**
- `server/.../auth/SessionManager.kt" - Token-based session management
- `server/.../automation/AutomationManager.kt" - User state, lamp overrides, heartbeat

**UI Files Created (Phase 5 & 6):**
- `composeApp/.../network/ApiClient.kt" - Multiplatform Ktor client with all API methods
- `composeApp/.../auth/SessionStorage.kt" - Token storage with StateFlow
- `composeApp/.../viewmodel/AuthViewModel.kt" - Login state management
- `composeApp/.../viewmodel/LampsViewModel.kt" - Lamp state and control management
- `composeApp/.../ui/LoginScreen.kt" - Password input with error handling
- `composeApp/.../ui/MainScreen.kt" - Full lamp control interface
- `composeApp/.../ui/LampCard.kt" - Individual lamp card with toggle, brightness slider
- `composeApp/.../ui/ServerConnectScreen.kt" - Initial server URL input screen

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
Client (composeApp) <--HTTP/WS--> Server (Ktor) <--REST API--> Philips Hue Bridge
```

The server acts as a persistent process that:
1. Maintains connection to Hue bridge
2. Runs automation (daylight simulation)
3. Provides API for the client UI
4. Handles 10-minute heartbeat for automation persistence

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

### Phase 4: Automation Engine - MOSTLY COMPLETE
- [x] Sunrise/sunset calculation based on region (basic algorithm)
- [x] Daylight simulation algorithm (wake -> sunset -> wind-down)
- [x] Heartbeat coroutine (10 min) for state persistence
- [x] Manual override tracking (1 hour timeout)
- [x] Timezone support (configurable via .env)
- [ ] Entertainment area detection (Hue Sync support) - TODO: needs API polling

### Phase 5: UI - Auth Screen - COMPLETE
- [x] Password input field
- [x] Session token storage (in-memory with StateFlow)
- [x] Error handling and feedback
- [x] API client with all endpoints (multiplatform)
- [x] AuthViewModel for login state
- [x] Navigation between login and main screens
- [ ] Hue bridge linking UI (if needed) - deferred to later

### Phase 6: UI - Lamps Screen - COMPLETE
- [x] Lamp list with current states (LazyColumn)
- [x] Individual lamp controls (toggle switch, brightness slider)
- [x] Color indicator for lamp state
- [x] "Turn all on/off" buttons
- [x] "I woke up!" / "I'm asleep!" toggle button
- [x] Pseudo-sunset time display
- [x] Manual override indicator with clear button
- [x] LampsViewModel with full state management
- [x] Server connection screen (initial URL prompt)
- [ ] Color picker - deferred (basic color indicator implemented)

### Phase 7: DevOps - COMPLETE
- [x] Dockerfile for server (multi-stage build with JDK 21)
- [x] docker-compose.yml with environment configuration (host network mode for Hue discovery)
- [ ] HTTPS setup documentation (Let's Encrypt) - optional, use reverse proxy
- [x] HTTPS configuration support (keystore.jks + .env password) - partial (waiting for Ktor 3 migration)
- [x] .gitignore updates for security (.env added)

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
KEYSTORE_PASSWORD=<optional>
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