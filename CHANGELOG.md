# 0.2.0 — Seamless Headphones

- Renamed the product; retained internal IDs and secret storage for update compatibility.
- Added new-playback automatic switching, source filters, two handoff modes, debounce, manual priority, cooldown and error suspension.
- Added Android media-session and call-state permissions, Mac process-level CoreAudio observation, guarded BLE recovery and sleep/wake handling.
- Rebuilt both native interfaces with four sections, system/light/dark themes, three accents, onboarding, settings, history and diagnostics.
- Added policy and debounce unit tests plus an Android instrumentation regression test for cold launch, navigation and theme recreation.
- Physical BLE/audio compatibility remains unverified; Android A2DP control can be denied by the OS. Builds use development signatures.
