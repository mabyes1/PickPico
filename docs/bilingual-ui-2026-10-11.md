# PickPico 0.17.9 — English and Traditional Chinese

Both languages ship in the same APK. English remains the default resource fallback;
Traditional Chinese lives in `values-b+zh+Hant`. Settings → Language / 語言 offers
Follow system, 繁體中文, and English. No settings or pairing reset is involved.

Android 13+ uses `LocaleManager` and declares supported app locales, so the system
App languages page and the in-app selection share the same setting. Android 8–12
uses a private language preference and Activity-specific configuration contexts.
The current Dashboard page survives language-driven recreation.

## Scope

443 extracted UI strings cover the Dashboard, capabilities, settings, ADB pairing
and connection flow, appearance, activity history, caller-return settings, Human
Help, and the Pico panel. Existing Android permission descriptions also have a
Traditional Chinese resource variant. Technical diagnostics, command identifiers,
and user/agent-authored task text retain their original content. The legacy
engineering console is not comprehensively localized in this release.

Navigation and capability icons keep stable identities across languages.
HomePulse accepts a display Context while retaining unchanged machine states and
English headless-call behavior. This avoids translating API state values or user
task content.

## Verification

- Gradle `:app:testDebugUnitTest :app:assembleDebug`: 245 tests passed, zero failures,
  errors, or skips. Includes five locale-specific tests: both resource variants,
  icon identity, preservation of task text/state, Android 8–12 preference behavior,
  Android 13 locale storage, and Chinese pairing-versus-connection display.
- S23 installed version `0.17.9`, code `135`, using `adb install -r` with the existing
  trusted Wi-Fi connection.
- Chinese Settings rendered on the device using Follow system; screenshot saved as
  `artifacts/bilingual-0.17.9-20261011/settings-zh.png`. Saved pairing remains visible
  separately from the current disconnected ADB transport.
- The initial phone verification task was lost during the app process restart on
  update. Replacement task: `task-087ccaa6-4806-4079-a802-0ea670f28d74`.
- Manual English/Chinese round-trip was deferred because another task began
  foreground clipboard acceptance on the same phone. Automated locale selection
  tests passed; only the Chinese Settings screen was visually checked on-device.
- Own task ended as cancelled after the partial device check. Final status:
  activeOperations=0, overlayHeld=false, wakeLockHeld=false. activeTasks=1 belongs
  to the other clipboard task; it was left intact.
- Original pairing timestamp `2026-10-08T04:34:14.676352Z` is unchanged. The app is
  paired; this UI update does not imply that its ADB transport is currently connected.

APK and build/test/hash evidence are in `artifacts/bilingual-0.17.9-20261011/`.
