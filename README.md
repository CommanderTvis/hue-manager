# Hue Manager

A Philips Hue lamp management system with daylight automation, built with Kotlin Multiplatform.

## Features

- **Daylight Simulation**: Automatically adjusts lamp brightness and color temperature throughout the day
- **Wake/Sleep Modes**: One-tap "I woke up!" and "I'm asleep!" actions
- **Multi-Platform Clients**: Desktop (JVM), Web (JS/WasmJS), and Android apps
- **Bridge Auto-Discovery**: Finds Hue bridges on your network via meethue.com
- **Manual Override Tracking**: Temporarily disables automation when you manually adjust a lamp
- **Docker Deployment**: Easy self-hosting with Docker Compose

## Architecture

```
┌─────────────────┐      HTTP     ┌──────────────────┐    REST API    ┌─────────────────┐
│  Client Apps    │◄─────────────►│   Ktor Server    │◄──────────────►│  Philips Hue    │
│  (Desktop/Web/  │               │                  │                │    Bridge       │
│   Android)      │               │  - Automation    │                │                 │
└─────────────────┘               │  - Session Auth  │                └─────────────────┘
                                  │  - Rate Limiting │
                                  └──────────────────┘
```

## Project Structure

| Module        | Description                                                     |
|---------------|-----------------------------------------------------------------|
| `server/`     | Ktor backend - Hue API integration, automation engine, REST API |
| `composeApp/` | Compose Multiplatform UI (Desktop, Web, Android targets)        |
| `androidApp/` | Android application entry point                                 |
| `shared/`     | Shared data models and API DTOs                                 |

## Quick Start

### 1. Configure Environment

Copy `.env.example` to `.env` and configure:

```bash
PASSWORD=your_secure_password
REGION=52.52,13.405  # latitude,longitude
PSEUDO_SUNSET=21:00  # when evening mode starts
TIMEZONE=Europe/Berlin
```

### 2. Run the Server

```bash
./gradlew :server:run
```

The server will auto-discover your Hue bridge. On first run, press the link button on your bridge when prompted.

### 3. Run a Client

**Desktop:**
```bash
./gradlew :composeApp:run
```

**Web (Wasm):**
```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

**Android:**
```bash
./gradlew :androidApp:assembleDebug
```

## Docker Deployment

```bash
docker compose up -d
```

The server uses host networking for Hue bridge discovery. Configure via environment variables in `docker-compose.yml`.

## API Endpoints

| Method | Endpoint           | Auth | Description                            |
|--------|--------------------|------|----------------------------------------|
| GET    | `/api/status`      | No   | Connection status and automation state |
| GET    | `/api/lamps`       | No   | List all lamps                         |
| PUT    | `/api/lamps/{id}`  | Yes  | Update lamp state                      |
| PUT    | `/api/lamps/all`   | Yes  | Update all lamps                       |
| POST   | `/api/session`     | No   | Login with password                    |
| POST   | `/api/wakeup`      | Yes  | Trigger "I woke up!"                   |
| POST   | `/api/sleep`       | Yes  | Trigger "I'm asleep!"                  |
| GET    | `/api/automation`  | No   | Automation status                      |
| POST   | `/api/bridge/link` | Yes  | Link Hue bridge                        |

## Daylight Automation

The automation engine simulates natural daylight patterns:

| Time Period         | Behavior                                              |
|---------------------|-------------------------------------------------------|
| Wake → Sunset       | Bright white light, compensating for outdoor darkness |
| Pseudo-sunset → +3h | Gradual transition to warm orange, dimming            |
| After wind-down     | Minimal orange light                                  |
| Sleep action        | All automated lamps off                               |

## Tech Stack

- **Kotlin/Multiplatform**
- **Ktor**
- **Compose Multiplatform**
