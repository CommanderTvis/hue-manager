# TASK

Maintain CLAUDE.md automatically as your memory to keep track of what needs to be done, project structure, etc.

When new tasks/changes/conventions/recommendations appear, implement them.

# Motivation

I have a problem with managing my Philips Hue lamps.

Creating native Hue automations for the workflow I want is tedious. Hue's native all-day scene feature works terribly because it forgets about its own existence if any lamp gets turned off, requiring me to take my phone every day.

Another huge problem is that there's effectively no way to control my lamps when the smartphone is turned off. Google Home web page does not have any precise control. There are no official desktop apps at all. Except Hue Light Sync that's needed for syncing music/video from Mac to Hue but can't control lamps!

# Design

This will be a project with a Ktor-based backend and Compose Multiplatform UI (web + desktop + android).

shared/ is for data model

server/ will be a constantly running process to be hosted on my VDS that will actively manage the state of lamps using Philips Cloud Hue API.

server/ also has to serve the web version of UI.

composeApp is UI. androidApp is Android subproject for UI

# UI design

## Screen Flows

**Desktop/mobile/web flow:**

Server choose (desktop/mobile only) -> Auth -> "Please authorize" screen (if bridge not linked via OAuth) -> Lamps

## Theme

The app has light/dark theme based on system preference.

## Server choose screen

On the local apps (desktop and android), there should be an initial prompt to give the Ktor server's URL. When a new URL is being entered, try connecting to check if the server is at least reachable.

The prompt has to store URL somewhere locally. Password has to be stored, too.

## Auth screen

Should prompt only for password. The password is stored in the .env file. The password has to be hashed on the first run of server and only the hash is stored in the .env.

## Bridge Authorization (OAuth2)

Bridge authorization is done via Philips Hue Remote API (OAuth2). The server connects to the bridge through Philips Cloud, so no local network access or port forwarding is required.

To authorize:
1. User visits `/api/hue/authorize` endpoint in browser
2. User logs in with their Philips Hue account
3. User presses the link button on their bridge when prompted
4. User clicks "Complete Setup" in the browser

The UI shows a "Please authorize" screen with these instructions when the bridge is not linked.

## Lamps screen (aka. main screen)

Should list all lamps of home and their current state.

Also, we should see the state of the automation scheduler ("Daylight mode", "Evening light") and what color does it dictate to all the lamps (show also as a small colored circle)

**Lamp card layout:** Compact single-row design with colored left border indicating lamp color/state. The brightness slider is inline with the lamp name, making cards shorter. Color picker expands below on demand. The colored left border provides a strong visual signal of each lamp's current color (orange for evening, warm white for daylight, gray for off/unreachable, accent color for Hue Sync).

Provide controls to turn on/off individual lamps to change their brightness and color (there should be a RGB color picker and a hex text field, 6 symbols of length max, without ability to type invalid characters in it. e.g. "f" is fine, "z" is not).The color picker should have a preview square of the selected color which also has to react to changes in hex field, too. Changes to color should be immediate right as user clicks the picker or enter a valid color to text field. There should be no "Set" button or anything like that.

Manual changes should be respected only for 1 hour unless a special control is pressed, then we are going back to automation. There should be a control to clear manual override. After clicking that control, the lamp should return to the state dictated by schedule immediately.

The controls of lamps for which something changes should gray out to avoid the messy experience when you change something, then not sure whether app is lagging or why lamp hasn't changed, etc. For example, I change color, the controls gray out until we are sure the change was written. Graying out applies across clients.

Unreachable lamps show a gray status indicator with controls disabled and brightness/color hidden.

**"Lamps on/off" button:** Prominent, full-color button in the status bar. Uses primary color for "Lamps on" and error/red color for "Lamps off" to clearly indicate the action.

The "Clear override" button should also appear when a lamp is detected as out-of-sync with automation (e.g. changed externally, via the official Hue app).

**Version display:** The client's build commit hash is shown in the bottom-right corner of the main screen (small, low-opacity monospace text).

**Scheduler editor popup:** Accessible from the main screen (e.g. a "Schedule" or gear button near the automation status). Opens a popup/dialog where the user can configure:

- **Pseudo-sunset time** — when "Evening light" begins (e.g. 21:05). Time picker or text input.
- **Automation state colors and brightness:**
  - *Daylight* (sun down, before pseudo-sunset) — color and brightness (default: warm white 100%)
  - *Evening* (pseudo-sunset to +3h) — color and brightness (default: #FF5500 100%)
  - *Night* (pseudo-sunset +3h until sunrise) — color and brightness (default: #FF5500 1%)

Changes are saved via `PUT /api/settings` and take effect immediately on the automation. The popup should use the same color picker and hex input as lamp cards.

## Automation pipeline

Instead of dumb manual scheduling that is offered by Philips Hue, there also should be kind of a perfect daylight emulator.

E.g. after "I woke up!" is pressed, the auto-managed lamps should be in the real time team play with sun.

The only parameters of full daylight simulation are:

- where we are in the world (region)
- time for pseudo-sunset (should be written by user somewhere, probably on the lamps screen. e.g. 21:05)

Sunrise and sunset are calculated using the NOAA Solar Calculator algorithm based on the configured region.

The auto-compensation logic is simple: when the sun is not up, lamps are on at 100% warm white. When the sun is up, lamps are off. Then at the chosen pseudo-sunset (e.g. 21:05), lamp switches to orange mode. Total actual workflow:

- 6:00 - user presses "I woke up!", sun not risen yet → 100% warm white
- 8:00 - sun rises → lamps turn off
- 17:00 - actual sunset → 100% warm white
- 21:05 - "pseudo sunset" → bright orange #FF3300 100% brightness
- 00:05 (pseudo sunset + 3 hours) → dim orange #FF5500, 1% brightness

The UI name of pseudo sunset is "Evening light".

When user (in the main screen) presses "I'm asleep", lamps are just turning off.

### Automation persistence

I guess that we need some kind of 10 min heartbeat coroutine here.

This is the key feature: when lamp is turned off and then turned on again, server has to notice it's on and restore the automation.

Also, I want to keep using the official Hue Sync application. When entertainment area of light Sync is used, automation should forget about the lamps. When Hue Sync is stopped, automation should wake up for those lamps. The UI of the main screen has to show for each lamp that it's being taken over by Hue Sync in real time and gray out the manual controls of it. Probably completely hide the color and brightness views of the lamp. Color indicator circle and on/off toggle have to be both not displayed for Hue Sync controlled lamps

## State sync

- **Fast poll (500ms):** `/api/sync` — lightweight endpoint returning automation state and pending lamp IDs (no Philips calls, served from cache)
- **Slow poll (10s):** `/api/lamps` — actual lamp states from cache

Pending operations are tracked server-side so all connected clients see which lamps are mid-operation to gray them out. Pending states auto-expire after 5 seconds as a safety net.

## In-memory lamp state cache

The server maintains an in-memory cache of all lamp and group state, refreshed every 5 seconds from Philips. All read endpoints return instantly from cache. After successful writes, the cache is optimistically patched so subsequent reads reflect the change without waiting for the next refresh.

# HTTP MCP server

In my chats with Claude, I want to do basically anything I can do with the app.

Type of MCP has to be HTTP to be connected like `{"url":"<domain>/mcp"}`. So should be served by server/.

After MCP is connected, we need to go to a OAuth page with password prompt without which MCP will fail. If the main SPA is already authorized, then just "Authorize" confirmation should be requested by the OAuth server for MCP without password entry.

MCP methods should surface:
- list lamps (also as a `hue://lamps` resource)
- see lamp state (in automation? in Hue Sync? in manual override?)
- write lamp state (as manual override, with color support)
- write state to all lamps at once (with color support)
- clear lamp override
- get automation status
- wake up / go to sleep

Copyable MCP URL should be provided to the user by a clickable link on popup available through "MCP" link on the main screen.

# DevOps and security

The server has to be fully buildable + deployable with Dockerfile. Meaning that I can just do `git clone ... && cd hue-manager` and `docker compose up -d` to run the server.

Configure GitHub Actions to build snapshots from master. Images have to be tagged by commit hash, we don't need versions currently. The visibilty of pushed containers has to be private. Also, make sure the workflow caches the Docker layers properly.

## Desktop app distribution

The desktop (macOS) app is built as a DMG and published via GitHub Actions:

- On push to `master`, build a DMG, computes its SHA256, and uploads it as a "nightly" GitHub Release.
- A Homebrew Cask - on orphan `brew` branch. The CI automatically updates the Cask version, SHA256, and asset ID after each build.
- Install with: `brew install --cask commandertvis/hue-manager/hue-manager`

Authentication session has to be secure. Be vigilant because it's a private home management system.

Use .env (gitignored) and .env.example (to show structure .env) for configuration of necessary API secrets, including password. Also .env should have region for geo features. Time zone also has to be stored in .env. The default one has to be Europe/Berlin.


## Rate limiting

Philips Hue Remote API rate limits are enforced server-side with rate limiters (10 req/s for lights, 1 req/s for groups). Client-side rate limiting is also applied for discovery.

## Misc

- Don't automatically commit changes unless requested in prompt.
- Avoid using Gradle without daemon.
