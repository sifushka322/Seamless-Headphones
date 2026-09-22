# Seamless Headphones

Move your headphones between **Mac and Android**. Native Swift/SwiftUI and Kotlin apps coordinate over authenticated BLE, without accounts, a server, or a shared Wi-Fi network. This is an independent implementation; no PodSwitch code is used. Windows is not supported.

**0.5.0 - Stable Production Release.** The stable release channel uses parallel handoff, confirmed automation controls, clearer connection states, and Russian/English interfaces. Update both apps. See [release validation](docs/validation-0.5.md) for checks and limitations.

The release designation describes the selected software channel. The locally generated installers still use ad hoc signing on Mac and the existing Android debug certificate to preserve upgrade compatibility. They are not notarized or store-distributed production installers.

## Features

- Start connecting the receiver while disconnecting the source; finish only after both sides confirm.
- Follow new playback, or transfer only when the current source is paused. The Mac confirms the shared rule selected from either device.
- Filter playback apps, detect new playback after 0.5 seconds, or use a 2-5 second delay. Configure cooldown and manual-command priority.
- Block transfers during calls, microphone use, a user block, missing permissions, or stale peer state.
- Pause automation after an error. Recover explicitly without replaying old playback events.
- Reconnect after temporary BLE failures with bounded retries on Android.
- Overview, Automation, Devices, and Settings on both platforms; light, dark, and system themes; Russian, English, and system language selection.
- Store pairing secrets in Keychain/Android Keystore and authenticate commands with HMAC-SHA256, session binding, and replay protection.

## Setup

1. **Mac:** macOS 14 or newer. The current DMG targets Apple Silicon. Move `Seamless Headphones.app` to Applications. The local build is ad hoc signed and not notarized.
2. **Android:** Android 12 or newer (API 31). Install the APK over the previous version to preserve settings and pairing.
3. Pair the headphones with both operating systems. Select the same Bluetooth address in both apps under Devices; matching names alone are insufficient.
4. Enable the link on Mac and show its QR code. On Android, scan the QR under Devices, confirm the key, and find the Mac. Manual key entry is also available. Grant Nearby Devices/Bluetooth and notification permissions; the camera is only used for QR scanning.
5. Under Automation on Android, enable notification access for the media-session API and allow phone-state access for call protection. The app does not read notification contents, phone numbers, contacts, or call history.
6. Test manual handoff in both directions and check your player's audio. Some Android firmware denies A2DP control; the app does not bypass that restriction.
7. Enable automation on both devices. Once ready, pause and **start playback again** on the receiving device. Existing playback and starts during cooldown are not queued for a later takeover.

Settings includes Language and Appearance. System language uses Russian for a Russian system preference and English otherwise. Device and player names remain unchanged.

Closing the Mac window leaves the menu-bar app running. Android uses a foreground service with a stop action. Starting a link is explicit after app restart. Android retries a temporary failure up to eight times; a new manual search starts a fresh retry budget.

## Controls and diagnostics

Parallel handoff is the fixed connection method. The automation rule controls *when* to transfer, not *how* to connect. Selecting a rule does not immediately move audio. Android shows the confirmed rule, pending acknowledgement, or an error. Block Switching prevents both manual and automatic handoffs; it does not connect headphones by itself.

Reset Pause clears an error/cooldown when prerequisites are satisfied. It does not enable disabled automation or bypass a user block. Each unavailable action explains its reason.

Enable Technical Logs on both devices, reproduce one transfer, and copy both reports. Each device retains up to 1,000 technical entries in memory. Journals are not synchronized: a shared outcome may appear on both devices, tagged with its origin. Reports include device addresses, transaction stages, and BLE timing; never secrets, QR payloads, signed frame contents, or audio. Technical identifiers and raw diagnostics are retained for debugging.

## Boundaries

BLE transports commands; the operating systems transport audio over Bluetooth. Both devices must be nearby and awake. No internet or common Wi-Fi network is required.

Mac observes CoreAudio process activity; Android observes media sessions. Neither proves that music is audible. A player with a continuously open stream can hide a new Play event; ads in an allowed browser can look like music. Ambiguous shared WebKit helpers cannot initiate handoff without a reliable source identity. Their observed audio still protects the source in idle-only mode.

Android checks A2DP and its own silent AudioTrack route, not every third-party player's output. Mac verifies the selected CoreAudio output against the headphone address. The app does not transfer tracks, playback positions, or queues, and does not press Play/Pause in another app.

Call/microphone signals depend on the OS. Call handoff is not implemented. An accepted system connection can complete after cancellation; uncertain operations are not automatically rolled back. A lost route is reported without forcibly taking audio back.

HMAC authenticates commands; it does not encrypt BLE metadata. This local protocol has not had an external security audit. Android requests no INTERNET permission and uses no analytics, audio recording, or accessibility service.

## Build and test

Mac requires Command Line Tools and a modern installed macOS SDK:

```sh
bash scripts/test-macos.sh
bash scripts/build-macos.sh --dmg
```

`MACOS_SDK` overrides the SDK path. The deployment target is macOS 14.0; the build architecture follows the host. Missing process-observation APIs disable automation at runtime.

Android uses JDK 17, SDK 35, Build Tools 35.0.0, Gradle 8.11.1, AGP 8.9.1, and Kotlin 2.1.20:

```sh
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
# With a test emulator or device connected:
./gradlew connectedDebugAndroidTest
```

`bash scripts/build-android.sh` uses `.tools` or the configured JAVA_HOME, ANDROID_HOME, and GRADLE_BIN, then copies the APK to `dist`. SDKs, keys, caches, and installers are excluded from Git. Store distribution requires a permanent Android release-signing configuration and Mac Developer ID/notarization.

## Repository

| Path | Purpose |
|---|---|
| `macos/Sources` | SwiftUI, automation policy, CoreAudio, BLE peripheral, coordinator |
| `android/app/src/main` | Kotlin UI, media sessions, BLE central, service, A2DP |
| `localization` | Shared translations of user-facing state and errors |
| `protocol/vectors.json` | Shared protocol vectors with synthetic keys |
| [Protocol](docs/protocol.md) | Authentication, messages, transactions, stale-event protection |
| [Release validation](docs/validation-0.5.md) | Audit findings, verification, hardware limitations |
