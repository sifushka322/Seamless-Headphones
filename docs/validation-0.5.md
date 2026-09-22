# 0.5.0 - Stable Production Release validation

## Release scope

The stable channel fixes the connection method to parallel handoff, adds English and Russian presentation, and resolves the observed theme/control problems. The saved experimental receiver-first preference is ignored. Sequential handoff remains available internally for legacy peers.

The release-channel label does not imply store certification. Local Mac builds remain ad hoc signed and unnotarized; Android keeps the existing debug signing certificate for upgrade compatibility. Physical Bluetooth validation remains separate from automated UI and protocol checks.

## UX audit and fixes

- Explicit Android light/dark themes no longer inherit an unwanted system force-dark transformation. System appearance follows live OS changes. Mac aligns AppKit window/native-control appearance with SwiftUI.
- Shared automation rules show their confirmed value, pending acknowledgement, failure or timeout. Changing a rule affects the next playback start, not the current route. A repeated selection does not reset playback observation.
- Manual actions explain missing connection, headphone mismatch, call protection, a user block, stale state, and busy transfers. Reset Pause no longer claims to enable disabled automation.
- A user block without an active transfer does not create an invisible cooldown. Any remaining cancellation cooldown has a visible countdown after safety checks.
- Transfer progress reflects concurrent release/acquire stages. A previously confirmed route is invalidated after a sustained loss. No automatic attempt steals audio back merely because the route disappeared.
- Ambiguous WebKit and unidentified helpers cannot initiate handoff or appear as selectable players. Their audio activity still protects an already-playing source in idle-only mode. Playback reported only under a shared helper can use manual handoff.
- Transient BLE timeouts retry according to a typed failure category; authentication failures stop. Disabling reconnect cancels a scheduled search. A new manual search resets the retry budget.
- A confirmed rejected A2DP disconnect releases the pending-operation guard. Transitional/unknown states remain protected. A late response cannot clear another operation's guard.
- English/Russian/System selection persists across restarts. System selects Russian for a Russian language preference and English otherwise. Device names, application names and protocol identifiers remain unchanged.

## Verification

Before localization, the UX changes passed 20 Swift protocol checks, 34 automation checks, 58 handoff checks, 13 UX-policy checks and three QR/queue groups; 32 Kotlin unit tests; and six Android UI tests. Thirteen Mac preview renders covered appearance and paused, blocked, disabled and busy states. Android tests covered explicit light under a dark OS, explicit dark under a light OS, live system appearance, navigation, clipboard, QR, persisted settings and unavailable controls.

Final bilingual build results:

- All Swift checks above passed again, together with 21 localization checks covering English/Russian/system selection, preference persistence, countdown/progress/history, opaque device names and literal placeholder characters.
- All 32 Android unit tests and 8 instrumentation tests passed. The latter cover English on all four pages, dialogs/QR, language persistence and return to Russian, live service status with Bluetooth permission, source lists, history, names, and the full theme matrix.
- Android lint: 0 errors, 40 warnings, 1 informational finding. Existing resource/KTX/style improvement warnings remain.
- The translation validator passed 274 shared, 154 Mac UI and 169 Android UI entries, checking duplicates, placeholder parity, and untranslated English values.
- Four English Mac pages and a Russian settings page were visually checked. The packaged Mac app also rendered English when launched from a separate temporary directory, proving that catalogs ship inside the app.
- Mac code-signature/DMG integrity checks and Android APK signature verification passed. Installers are in `dist`; SHA-256 checksums are provided separately.

## Hardware evidence and limits

The user's 0.4.0 log showed one parallel transfer to Mac in 2.45 seconds and one to Android in 5.48 seconds, excluding playback detection. These are individual observations, not latency guarantees. A2DP behavior depends on the headset and OS, and a route check does not prove audible output in every third-party player. Automated tests cannot replace a physical Mac/Android/Buds4 Pro round trip.
