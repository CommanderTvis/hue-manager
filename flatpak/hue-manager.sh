#!/bin/sh
# Flatpak entry point: launch the jpackage app image (bundled JRE included).
# The launcher binary is named after the Compose packageName ("Hue Manager"),
# so resolve it dynamically instead of hardcoding the name.
exec "$(find /app/dist/bin -maxdepth 1 -type f | head -n 1)" "$@"
