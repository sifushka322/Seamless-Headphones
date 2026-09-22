# 0.3.1 - Automation diagnostics and reset

- Replace fixed two-minute manual suppression with configurable priority, default 15 seconds; remove independent Android cooldown.
- Explicit reset clears both peers' timers and baselines without replaying existing playback.
- Identify Android voice guard reasons and raw observations. Relax communication mode only with idle telephony, no observed voice/recording, and active music.
- Show observed/allowed players and newly discovered Mac sources; retain state transitions instead of repetitive heartbeat logs.
- Add diagnostic-copy button at the top of every Android page.
- Replace tiny navigation glyphs with separate 24 dp vector icons and 72 dp touch targets.
- Poll route readiness sooner without reporting success before confirmation.
- Use the approved sidebar earbuds symbol for both launcher icons.
- Physical automation needs retesting on Buds4 Pro; see docs/validation-0.3.1.md.

# 0.3.0 — Pairing and diagnostics

- Added QR pairing on Mac and an offline Android camera scanner with confirmation before storing the key.
- Deduplicated both headset lists by normalized Bluetooth address and displayed local/remote selections. A name match alone never authorizes a transfer.
- Added opt-in technical logs on each device: source labels, transaction stages, frame sizes, queue depth, MTU and A2DP diagnostics. Secrets and raw frames are excluded.
- Coalesced unsent BLE snapshots, prioritized commands at frame boundaries and negotiated Android MTU, preserving the 20-byte fallback and authenticated ordering.
- Disabled repeated Android requests while waiting for Mac and Mac menu commands during transfers.
- Reworked Android navigation, transfer controls, device labels and log panels; added a two-earbud icon on both platforms.
- Physical switching with Buds4 Pro still needs to be tested again. The reported failure indicated mismatched headset addresses.

# 0.2.0 — Seamless Headphones

- Renamed the product; retained internal IDs and secret storage for update compatibility.
- Added new-playback automatic switching, source filters, two handoff modes, debounce, manual priority, cooldown and error suspension.
- Added Android media-session and call-state permissions, Mac process-level CoreAudio observation, guarded BLE recovery and sleep/wake handling.
- Rebuilt both native interfaces with four sections, system/light/dark themes, three accents, onboarding, settings, history and diagnostics.
- Added policy and debounce unit tests plus an Android instrumentation regression test for cold launch, navigation and theme recreation.
- Physical BLE/audio compatibility remains unverified; Android A2DP control can be denied by the OS. Builds use development signatures.
