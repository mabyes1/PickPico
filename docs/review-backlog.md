# PickPico review backlog

The P1 items deferred after the 2026-09-03 P0 repair pass are now implemented:

- The official software-update channel is independent from the user-configured Remote Relay URL.
- Android 8 compatibility no longer calls API 28/33-only methods without a fallback, and revoked BLE permissions are handled safely.
- Settings explains update failures, setup requirements, and download progress.
- The top status dot reflects node/relay state, and capability readiness uses the live setup states shown by the app.
- `CLEAR INBOX` requires confirmation and is disabled when the inbox is empty.
- Color fields open the color picker across the whole control instead of looking editable while doing nothing.
- Inbox uses the same bottom-navigation destinations as the dashboard.
- Debug builds now resolve the signed-in user's Android keystore explicitly, and readiness/publish refuse APKs signed with a different certificate.
