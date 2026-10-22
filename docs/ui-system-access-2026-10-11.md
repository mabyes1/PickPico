# System access UX — 0.17.8

The agent selects the suitable tool for each task. Dedicated phone capabilities handle ordinary interactions; ADB adds Android shell, file transfer, and package operations. Users should not have to choose a tool transport before asking for work.

## Changes

- Settings separates **Agent connection** (remote access to Pico) from **Android system access** (additional device operations).
- System Control keeps saved pairing and current transport status visible as separate rows.
- A paired device opens a compact everyday-use screen. Android settings and an optional reconnect action are available without scrolling through pairing instructions.
- Pairing instructions and the code input are shown during initial setup. After successful pairing they collapse under **Pairing settings**, which can be reopened explicitly.
- A connected device displays a disabled **Connected** action. A disconnected paired device offers **Reconnect now**, while explaining that agent commands also reconnect automatically.
- Copy explains that camera, notification, and screen tools remain usable with their normal permissions when ADB is unavailable.

Existing theme, permissions, pairing keys, and connection engine are preserved. This iteration changes the Settings/System Control flow; it is not a redesign of every app screen.

## Validation

- `:app:testDebugUnitTest --tests com.mcpocket.poc.DashboardPicoAdbStatusTest :app:assembleDebug` passed after the final source change. Both targeted tests passed (0 failures / 0 errors).
- `git diff --check` passed.
- APK: `artifacts/ui-0.17.8-20261011/PickPico-0.17.8-debug.apk`, version 0.17.8 / code 134.
- SHA-256: `F6B3E3A03B2BBB5F46A3AE33CB42DB3A81AF9CC2B6D15D29F8C4BB6053AC1D0E`.
- Installation and rendered-device verification remain pending: the phone is in another active PicoBridge 0.9.2 acceptance task (`task-9bd11e61-dc1f-4b89-a4c0-35b47349d1eb`, waiting for human media-key verification). This iteration did not change the phone or acquire a screen hold. The last observed installed PickPico remains 0.17.7.
