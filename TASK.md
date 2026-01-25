# TASK

Maintain CLAUDE.md automatically as your memory to keep track of what needs to be done, project structure, etc.

When new tasks/changes/conventions/recommendations appear, implement them.

# Motivation

I have a problem with managing my Philips Hue lamps.

Creating native Hue automations for the workflow I want is tedious. Hue's native all-day scene feature works terribly because it forgets about its own existence if any lamp gets turned off, requiring me to take my phone every day.

Another huge problem is that there's effectively no way to control my lamps when the smartphone is turned off. Google Home web page does not have any precise control. There are no official desktop apps at all. Except Hue Light Sync that's needed for syncing music/video from Mac to Hue but can't control lamps!

# Design

This will be a project with a Ktor-based backend and Compose Multiplatform UI (web + desktop + android).

shared is for data model

server/ will be a constantly running process to be hosted on my VDS that will actively manage the state of lamps using Philips Cloud Hue API.

server/ also has to serve the web version of UI.

composeApp is UI.

# UI design

## Screen Flows

**Desktop/mobile/web flow:**

Server choose (desktop/mobile only) -> Auth -> "Please authorize" screen (if bridge not linked via OAuth) -> Lamps

## Server choose screen

On the local apps (desktop and android), there should be an initial prompt to give the Ktor server's URL. When a new URL is being entered, try connecting to check if the server is at least reachable.

The prompt has to store URL somewhere locally. Password has to be stored, too.

## Auth screen

Should prompt only for password. The password is stored in the .env file.

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

Also, we should see the state of the automation scheduler ("Auto-compensating", "Evening light") and what color does it dictate to all the lamps (show also as a small colored circle)

Provide controls to turn on/off individual lamps to change their brightness and color (there should be a RGB color picker and a hex text field, 6 symbols of length max, without ability to type invalid characters in it. e.g. "f" is fine, "z" is not).The color picker should have a preview square of the selected color which also has to react to changes in hex field, too. Changes to color should be immediate right as user clicks the picker or enter a valid color to text field. There should be no "Set" button or anything like that.

Manual changes should be respected only for 1 hour unless a special control is pressed, then we are going back to automation. There should be a control to clear manual override. After clicking that control, the lamp should return to the state dictated by schedule immediately.

The controls of lamps for which something changes should gray out to avoid the messy experience when you change something, then not sure whether app is lagging or why lamp hasn't changed, etc. For example, I change color, the controls gray out until we are sure the change was written.

And a toggle on/off control for all lamps at once, no matter automation e.g. "I left home" switching to "I am back." This control should also cause automation to be paused and to grey out individual lamp controls.

There should be "I woke up!" button that turns into "I'm asleep!"

## Automation pipeline

Instead of dumb manual scheduling that is offered by Philips Hue, there also should be kind of a perfect daylight emulator.

E.g. after "I woke up!" is pressed, the auto-managed lamps should be in the real time team play with sun.

The only parameters of full daylight simulation are:

- where we are in the world (region)
- time for pseudo-sunset (should be written by user somewhere, probably on the lamps screen. e.g. 21:05)

E.g. if the user wakes up at 6:00 and the sun has not risen yet, the lamps go super white compensating it. Then sun rises and lamps dim to economize energy. Then at the chosen pseudo-sunset (e.g. 21:05), lamp goes from "auto-compensate bright" to "no blue photons" or "Bright orange" mode and then to dim orange mode, so total actual workflow may look like this:

- 6:00 - user presses "I woke up!", we go super bright #FFFFFF 100%
- 8:00 - sun rises, lamps go dimmer #FFFFFF 
- 12:00 - sun is shining, lamps turn off
- 17:00 - actual sunset, lamps are super bright
- 21:05 - "pseudo sunset", lamps are going orange ##FF3300 100% brightness (for the whole time before "you need to sleep period!)
- 00:05 (pseduo sunset + 3 hours) - lamps are preparing user to sleep, ##FF5500, 1% brightness

The UI name of pseudo sunset is "Evening light".

When user (in the main screen) presses "I'm asleep", lamps are just turning off.

### Automation persistence

I guess that we need some kind of 10 min heartbeat coroutine here.

This is the key feature: when lamp is turned off and then turned on again, server has to notice it's on and restore the automation.

Also, I want to keep using the official Hue Sync application. When entertainment area of light Sync is used, automation should forget about the lamps. When Hue Sync is stopped, automation should wake up for those lamps. The UI of the main screen has to show for each lamp that it's being taken over by Hue Sync in real time and gray out the manual controls of it. Probably completely hide the color and brightness views of the lamp. Color indicator circle and on/off toggle have to be both not displayed for Hue Sync controlled lamps

# HTTP MCP server

Potential things to do, use case.

In my chats with Claude, I want to do basically anything I can do with the app in reasonable boundaries.

Type of MCP has to be HTTP to be connected like {"url":"<domain>/mcp"}. So should be served by server/.

After MCP is connected, we need to go to a OAuth page with password prompt without which MCP will fail.

Potential MCP methods surface:
- list lamps
- see lamp state (in automation? in Hue Sync? in manual override?)
- write lamp state (as manual override)
- write state to all lamps

Copyable MCP JSON with already proper URL patched in should be to the user provided by a clickable link on the main screen.

# DevOps and security

The server has to be fully buildable + deployable with Dockerfile. Meaning that I can just do `git clone ... && cd hue-manager` and `docker compose up -d --build` right in the root directory to run the server.

Configure GitHub Actions to build snapshots from master. Images have to be tagged by commit hash, we don't need versions currently. The visibilty of pushed containers has to be private. Also, make sure the workflow caches the Docker layers properly.

Authentication session has to be secure, I don't know exactly how. Anyway, be vigilant because it's a private home management system.

Use .env (gitignored) and .env.example (to show structure .env) for configuration of necessary API secrets, including password. Also .env should have region for geo features. Time zone also has to be stored in .env. The default one has to be Europe/Berlin.

## Misc

- Philips Hue has tight rate limits, especially for discovery. Make sure to throttle requests.
- Don't automatically commit changes unless requested in prompt.
- Avoid using Gradle without daemon.
