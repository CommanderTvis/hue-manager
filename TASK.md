# TASK

**AI agent, this file has to be immutable and modified only by humans.**

Maintain CLAUDE.md automatically as your memory to keep track of what needs to be done, project structure, etc.

# Motivation

I have a problem with managing my Philips Hue lamps.

Creating native Hue automations for the workflow I want is tedious. Hue's native all-day scene feature works terribly because it forgets about its own existence if any lamp gets turned off, requiring me to take my phone every day.

Another huge problem is that there's effectively no way to control my lamps when the smartphone is turned off. Google Home web page does not have any precise control. There are no official desktop apps at all. Except Hue Light Sync that's needed for syncing music/video from Mac to Hue but can't control lamps!

# Design

This will be a project with a Ktor-based backend and Compose Multiplatform UI (web + desktop + android).

shared is for data model

server/ will be a constantly running process to be hosted on my VDS that will actively manage the state of lamps using Philips Hue Rest API. 

server/ also has to serve the web version of UI.

composeApp is UI.

# UI design

## Screen Flows

**Desktop/mobile flow:**

Server choose -> Auth -> Bridge pairing (if not done) -> Lamps

**Web app flow:**

Auth -> "Please pair" screen if bridge is not paired OR Lamps if paired

## Server choose screen

On the local apps (desktop and android), there should be an initial prompt to give the Ktor server's URL. When a new URL is being entered, try connecting to check if the server is at least reachable.

The prompt has to store URL somewhere locally. 

## Auth screen

Should prompt only for password. The password is stored in the .env file.

Then, if needed, it should start Hue bridge access acquisition (the one where you need to click on the button on the bridge) and memorize the keys for Hue.

Since the remote server can't discover a Hue bridge in my home, the app should be able to do it and grant to the server the actual IP of my Hue bridge.

Also, since the Web UI hosted on the remote server can't connect to the Bridge, it should handle the situation when Bridge is not paired yet properly.

## Lamps screen (aka. main screen)

Should list all lamps of home and their current state.

Provide controls to turn on/off individual lamps, to change their brightness and color.

Manual changes should be respected only for 1 hour unless a special control is pressed, then we are going back to automation.

And turn on/off all lamps at once, no matter automation e.g. "I left home" switching to "I am back."

There should be "I woke up!" button that turns into "I'm asleep!"

## Automation pipeline

Instead of dumb manual scheduling that is offered by Philips Hue, there also should be kind of a perfect daylight emulator.

E.g. after "I woke up!" is pressed, the auto-managed lamps should be in the real time team play with sun.

The only parameters of full daylight simulation are:

- where we are in the world (region)
- time for pseudo-sunset (should be written by user somewhere, probably on the lamps screen. e.g. 21:05)

E.g. if the user wakes up at 6:00 and the sun has not risen yet, the lamps go super white compensating it. Then sun rises and lamps dim to economize energy. Then at the chosen pseudo-sunset (e.g. 21:05), lamp goes from "auto-compensate bright" to "no blue photons" mode and get dimmer and dimmer, so total actual workflow may look like this:

- 6:00 - user presses "I woke up!", we go super bright #FFFFFF 100%
- 8:00 - sun rises, lamps go dimmer #FFFFFF 
- 12:00 - sun is shining, lamps turn off
- 17:00 - actual sunset, lamps are super bright
- 21:05 - "pseudo sunset", lamps are going orange ##FF5500 100% brightness
- 00:05 - lamps are preparing user to sleep, ##FF5500, 1% brightness

When user (in the main screen) presses "I'm asleep", lamps are just turning off.

### Automation persistence

I guess that we need some kind of 10 min heartbeat coroutine here.

This is the key feature: when lamp is turned off and then turned on again, server has to notice it's on and restore the automation.

Also, I want to keep using the official Hue Sync application. When entertainment area of light Sync is used, automation should forget about the lamps. When Hue Sync is stopped, automation should wake up for those lamps.

# HTTP MCP server

TODO, forget about it for now.

# DevOps and security

The server has to be fully buildable + deployable with Dockerfile. Meaning that I can just do `git clone ... && cd hue-manager` and `docker compose up -d --build` right in the root directory to run the server.

Use and auto-manage HTTPS automatically with Let's Encrypt certbot on compatible servers (if certbot is not installed, then forget about HTTPS and fallback to HTTP, that will be fine for debugging).

Configure GitHub Actions to build snapshots from master. Images have to be tagged by commit hash, we don't need versions currently. The visibilty of pushed containers has to be private. Also, make sure the workflow caches the Docker layers properly.

Authentication session has to be secure, I don't know exactly how. Anyway, be vigilant because it's a private home management system.

Use .env (gitignored) and .env.example (to show structure .env) for configuration of necessary API secrets, including password. Also .env should have region for geo features. Time zone also has to be stored in .env. The default one has to be Europe/Berlin.
