# Hue Manager - Project Memory

## Project Overview

A Philips Hue lamp management system with:
- **server/**: Ktor backend for managing lamp state via Philips Hue Remote API (OAuth2)
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
  - Ktor HTTP client for Hue Remote API
  - OAuth2 authentication with Philips Hue
  - Credential storage (updates .env automatically)
  - Basic API endpoints: `/api/status`, `/api/lamps`, `/api/groups`

**Server Files:**
- `server/.../config/Config.kt` - Configuration loading from .env
- `server/.../hue/HueClient.kt` - HTTP client for local Hue REST API (legacy, kept for reference)
- `server/.../hue/HueRemoteClient.kt` - HTTP client for Philips Hue Remote API (OAuth2)
- `server/.../hue/HueService.kt` - Service layer managing Hue connection via Remote API
- `server/.../hue/RateLimiter.kt` - Token bucket and minimum delay rate limiters

**Shared Models:**
- `shared/.../models/Lamp.kt` - Lamp, LampState, ColorMode, LampType
- `shared/.../models/Group.kt` - Group, GroupType, RoomClass
- `shared/.../models/Automation.kt` - AutomationState, UserState, LampOverride, SunTimes
- `shared/.../models/Session.kt` - Session, LoginRequest, LoginResponse
- `shared/.../api/ApiModels.kt` - API DTOs (StatusResponse, LampsResponse, etc.)

**Server API & Auth:**
- `server/.../auth/SessionManager.kt` - Token-based session management
- `server/.../automation/AutomationManager.kt` - User state, lamp overrides, heartbeat

**UI Files:**
- `composeApp/.../network/ApiClient.kt` - Multiplatform Ktor client with all API methods
- `composeApp/.../auth/SessionStorage.kt` - Persistent session token storage with StateFlow
- `composeApp/.../storage/ServerUrlStorage.kt` - Platform-specific server URL storage
- `composeApp/.../viewmodel/AuthViewModel.kt` - Login state management
- `composeApp/.../viewmodel/LampsViewModel.kt` - Lamp state and control management
- `composeApp/.../viewmodel/ServerConnectViewModel.kt` - Server URL validation
- `composeApp/.../ui/LoginScreen.kt` - Password input with error handling
- `composeApp/.../ui/MainScreen.kt` - Full lamp control interface
- `composeApp/.../ui/LampCard.kt` - Individual lamp card with toggle, brightness slider
- `composeApp/.../ui/ServerConnectScreen.kt` - Server URL input and validation
- `composeApp/.../ui/PleasePairScreen.kt` - OAuth2 pairing instructions

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
Client (composeApp) <--HTTP--> Server (Ktor on VDS) <--OAuth2/REST--> Philips Cloud (api.meethue.com) <--> Hue Bridge
```

**Bridge Connection via OAuth2:**
The server connects to the Hue bridge through Philips Cloud using OAuth2. No local network access, port forwarding, or VPN is required.

**OAuth2 Pairing Flow:**
1. User visits `/api/hue/authorize` endpoint in browser
2. User is redirected to Philips Hue login page
3. User logs in with their Philips Hue account
4. User presses the link button on their bridge when prompted
5. User clicks "Complete Setup" in the browser
6. Server stores OAuth tokens and can control lamps via Remote API

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
HUE_CLIENT_ID=<from developers.meethue.com>
HUE_CLIENT_SECRET=<from developers.meethue.com>
HUE_APP_ID=<from developers.meethue.com>

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

### OAuth2 (Bridge Pairing)
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
│   └── api/                 # API DTOs
├── composeApp/src/commonMain/kotlin/io/github/commandertvis/huemanager/
│   ├── App.kt               # Main app
│   ├── ui/                  # UI screens
│   ├── viewmodel/           # ViewModels
│   └── network/             # Server API client
├── androidApp/              # Android entry point
├── .env.example
├── Dockerfile
└── docker-compose.yml
```
