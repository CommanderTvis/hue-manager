# Hue Manager - Project Memory

> **Design Document:** See [TASK.md](TASK.md) for the human-written project design, motivation, and requirements specification.

## Project Overview

A Philips Hue lamp management system with:
- **server/**: Ktor backend for managing lamp state via Philips Hue Remote API (OAuth2)
- **composeApp/**: Compose Multiplatform UI library (Desktop JVM, Web JS/WasmJS, Android target)
- **androidApp/**: Android application module (depends on composeApp)
- **shared/**: Shared data models and constants

**Deployment:** Self-hosted on VDS with Docker + Caddy reverse proxy for HTTPS.

**IMPORTANT:** Philips Hue OAuth2 requires HTTPS and a valid domain name. The redirect URI must be publicly accessible over HTTPS.

## Tech Stack

- Kotlin 2.3.0
- Ktor 3.3.3 (server + client)
- Compose Multiplatform 1.10.0
- kotlinx-serialization 1.9.0
- kotlinx-datetime 0.7.1-0.6.x-compat
- kotlinx-coroutines 1.10.2
- dotenv-kotlin 6.5.1
- MCP Kotlin SDK 0.8.3
- Gradle with version catalog

**Build Configuration:**
- Maven Central and Google repositories only (no JitPack needed)
- Android context management via shared AndroidContext singleton

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
- `POST /api/auth` - Verify password

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

### Automation
- `GET /api/automation` - Get automation status
- `POST /api/wakeup` - "I woke up!" action
- `POST /api/sleep` - "I'm asleep!" action

### Settings
- `GET /api/settings` - Get current settings (pseudo-sunset time, location, automated lamp IDs)
- `PUT /api/settings` - Update settings (auth required)

**Note:** Settings API is fully implemented. Settings UI screen is not yet implemented - settings must be edited via API or in `.env` file.

### MCP (Model Context Protocol)
- `GET /api/mcp/oauth` - MCP OAuth-style authorization page
- `GET /api/mcp/oauth/.well-known/oauth-authorization-server` - OAuth metadata for connectors
- `POST /api/mcp/oauth/register` - OAuth dynamic client registration
- `POST /api/mcp/oauth/token` - OAuth token exchange
- `/api/mcp` - MCP SSE endpoint (auth required for tools)

## MCP (Model Context Protocol)

The server exposes an MCP endpoint for integration with Claude and other MCP-compatible clients.

**Connection:** Configure as `{"url":"<domain>/api/mcp"}` in your MCP client.

**Authentication:** Requires `Authorization: Bearer <password>`. For interactive auth (Claude connector), use `/api/mcp/oauth` as the authorization URL.

**Implementation:**
- Uses official MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk:0.8.3`)
- SSE transport is wired manually via `SseServerTransport` for correct endpoint advertisement
- SSE connection: `GET /api/mcp` (establishes connection, receives server messages)
- Client messages: `POST /api/mcp?sessionId=<id>` (sends client requests)
- Server file: `server/.../mcp/McpHandler.kt`

**Available Resources:**
| Resource URI | Description |
|--------------|-------------|
| `hue://lamps` | List all lamps with current state (on/off, brightness, color, automation status) |

**Available Tools:**
| Tool | Description |
|------|-------------|
| `get_lamp_state` | Get detailed state of a specific lamp including automation/override/Hue Sync status |
| `set_lamp_state` | Control a lamp (on/off, brightness, hue, saturation, color temperature). Creates 1-hour override. |
| `set_all_lamps` | Control all lamps at once (on/off, brightness). Creates overrides for all lamps. |
| `clear_lamp_override` | Clear manual override for a lamp, returning it to automation control |
| `get_automation_status` | Get current automation mode, user state, target color, and overridden lamps |
| `wake_up` | Trigger "I woke up!" action - starts daylight automation sequence |
| `go_to_sleep` | Trigger "I'm asleep!" action - turns off all automated lamps |

**UI Integration:**
- Main screen includes "MCP" button that opens a dialog with pre-configured MCP JSON
- Dialog displays MCP server configuration with the current server URL automatically filled in
- One-click copy button copies the MCP configuration to clipboard for easy setup in Claude or other MCP clients
- Platform-specific clipboard implementation (JVM, WasmJS, Android)

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
- OAuth2 and bridge linking calls are not rate-limited (one-time setup operations)

## Daylight Simulation Logic

```
Before pseudo_sunset: Bright white light (auto compensation)
pseudo_sunset -> pseudo_sunset+3h: Bright orange (#FF5500, 100% brightness)
pseudo_sunset+3h onwards: Dim orange light (1% brightness)
sleep_action: Turn off all automated lamps
```

**Automation Modes:**
- `AUTO_COMPENSATION` - Before pseudo-sunset: bright white light
- `EVENING` - From pseudo-sunset to +3h: bright orange light
- `NIGHT` - After pseudo-sunset+3h: dim orange light
- `USER_ASLEEP` - User pressed "I'm asleep!", lamps off

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

```
hue-manager/
├── server/src/main/kotlin/io/github/commandertvis/huemanager/
│   ├── Application.kt       # Entry point + routes
│   ├── auth/                # Session management
│   ├── automation/          # Daylight automation
│   ├── config/              # Environment configuration
│   ├── hue/                 # Hue API clients (Remote + Local)
│   └── mcp/                 # MCP server (McpHandler.kt)
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
│   ├── storage/             # Server URL storage
│   └── colorpicker/         # In-house HSV color picker (from colorpicker-compose)
├── composeApp/src/{jvmMain,wasmJsMain}/  # Platform-specific implementations
├── androidApp/              # Android entry point
├── gradle/libs.versions.toml  # Gradle version catalog
├── .env.example             # Environment configuration template
├── Dockerfile               # Multi-stage build for local docker compose
├── Dockerfile.runtime       # Runtime-only image for CI/CD (uses pre-built artifacts)
├── docker-compose.yml       # Docker deployment config (template)
├── Caddyfile.example        # Caddy reverse proxy config (HTTPS)
├── TASK.md                  # Human-written design document
└── CLAUDE.md                # This file - AI memory/context
```

## Key Server Files

- `server/.../config/Config.kt` - Configuration loading from .env
- `server/.../hue/HueClient.kt` - HTTP client for local Hue REST API (legacy, kept for reference)
- `server/.../hue/HueRemoteClient.kt` - HTTP client for Philips Hue Remote API (OAuth2)
- `server/.../hue/HueService.kt` - Service layer managing Hue connection via Remote API
- `server/.../hue/RateLimiter.kt` - Token bucket and minimum delay rate limiters
- `server/.../automation/AutomationManager.kt` - User state, lamp overrides, heartbeat, automation mode calculation
- `server/.../mcp/McpHandler.kt` - MCP server configuration and tool implementations

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
- `composeApp/.../auth/AuthStorage.kt` - Persistent password storage with StateFlow
- `composeApp/.../storage/ServerUrlStorage.kt` - Platform-specific server URL storage
- `composeApp/.../viewmodel/AuthViewModel.kt` - Login state management
- `composeApp/.../viewmodel/LampsViewModel.kt` - Lamp state and control management
- `composeApp/.../viewmodel/ServerConnectViewModel.kt` - Server URL validation
- `composeApp/.../ui/LoginScreen.kt` - Password input with error handling
- `composeApp/.../ui/MainScreen.kt` - Full lamp control interface with automation state display
- `composeApp/.../ui/LampCard.kt` - Individual lamp card with toggle, brightness slider, RGB color picker with hex input
- `composeApp/.../ui/ServerConnectScreen.kt` - Server URL input and validation
- `composeApp/.../ui/PleaseAuthorizeScreen.kt` - OAuth2 authorization instructions
- `composeApp/.../colorpicker/*.kt` - In-house HSV color picker implementation

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
- Container visibility inherits from repository (private repository → private packages)
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

## UI Features

**Main Screen:**
- Lists all lamps with current state
- Automation state display ("Auto-compensating", "Evening light") with target color indicator
- Per-lamp controls: on/off toggle, brightness slider, RGB color picker with hex input
- Per-lamp loading state (controls gray out during API calls)
- "Clear override" button for manual overrides or out-of-sync lamps
- "I woke up!" / "I'm asleep!" buttons
- "MCP" button for Claude integration setup

**Color Picker:**
- In-house HSV implementation (based on colorpicker-compose, customized and trimmed)
- Immediate color application (no "Set" button)
- Hex input validates characters (0-9, a-f only) and limits to 6 characters
- Preview square reflects changes from hex input and picker

**Error Handling:**
- 401 errors show "Incorrect password" instead of generic error
- Platform-specific URL opening for OAuth flow

## Recent Changes

**January 2026:**
- Migrated MCP from SSE transport to Streamable HTTP transport (`StreamableHttpServerTransport`)
- Bumped MCP Kotlin SDK from 0.8.1 to 0.8.3
- Simplified automation mode handling and fixed evening transition brightness
- Removed unused `initialServerUrl` parameter from App function
- Updated README with MCP resources and tool descriptions
- Removed separate colorpicker module, integrated components into composeApp
- Streamlined resource cleanup and enhanced code readability
- Fixed override indicator hidden when lamp is in entertainment mode
- Enhanced URL handling and validation in ServerUrlStorage and ApiClient
