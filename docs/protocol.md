# BLE protocol v1

Mac — CBPeripheralManager / GATT server and single transaction coordinator. Android — BluetoothGatt central. This avoids requiring BLE advertising support on the phone. Audio stays on OS-managed Classic Bluetooth A2DP. No external service, Wi-Fi or account is involved.

Service: `845E1000-7F5A-4CB5-9AE8-2DC18A64BB01`.
Write characteristic: `845E1001-7F5A-4CB5-9AE8-2DC18A64BB01` (write with response).
Notify characteristic: `845E1002-7F5A-4CB5-9AE8-2DC18A64BB01` (CCCD notifications).

## Enrollment and authentication

The Mac generates a 32-byte secret using Security.framework, stores it in Keychain and displays Base64 during explicit enrollment. The user transfers it to their Android. Android encrypts the secret with an AES-GCM key in Android Keystore; backup is disabled. The secret itself is never sent via BLE. Revocation replaces the Mac secret and closes the old session.

Mac allows one subscribed central, generates a fresh random UUID challenge and sends `HELLO|session\n`. Android authenticates with a signed `hello`; Mac responds with a signed `hello`. Neither side accepts application commands before this handshake. A subscription that does not authenticate expires in 12 seconds; discovery/handshake on Android expires in 25 seconds.

Authenticated frame, UTF-8 ASCII envelope:

```
1|session|sender|sequence|base64(payload-json)|base64(hmac-sha256)\n
```

HMAC covers the first five fields including their separators. Sender is `mac` or `android`; opposite direction is mandatory to prevent reflection. Sequence is strictly increasing within a fresh session. JSON fields: `type`, `id`, `target`, `detail`, `device`. Unknown transaction IDs and stage-inappropriate replies cannot start physical actions. Shared synthetic interoperability vectors live in `protocol/vectors.json`.

HMAC authenticates commands; it does **not encrypt metadata**. A nearby observer may see device addresses and status. This is a prototype, not a completed security review. A compromised trusted phone can request manual transfers. A nearby attacker can interfere with discovery or occupy a subscription temporarily, but cannot forge commands without the key.

Frames are bounded to 4096 bytes, reassembled until newline. Android requests MTU 185 before discovery and writes sequential chunks up to 180 bytes, waiting for write callbacks; failed negotiation retains the 20-byte fallback. Mac chunks to the central's notification limit, maximum 180 bytes, and respects CoreBluetooth backpressure. Each sender queues at most 32 unencoded commands plus one latest unsent snapshot per type (`activity`, `autoStatus`, `ping`, `pong`). Commands precede snapshots at frame boundaries. Authentication and sequence numbering happen at dequeue, so priority never reorders signed frames. Only one encoded frame is active. Reconnection discards buffers, counters, authentication and pending transactions. Heartbeats run every four seconds; no authenticated response for 14 seconds closes the session.

## Transfer

1. Local Mac button or authenticated Android `request(target)` asks the Mac coordinator. Only one transfer is active.
2. Mac validates local settings, pairing and microphone signal; sends `prepare(id,target,device)`.
3. Android validates hold, voice mode, selected headset identity and available profile adapter; replies `ready` or `error`. Method presence is only a preliminary check, not a guarantee that Android permits invocation.
4. Mac releases locally for target Android, or sends `release` for target Mac. Only confirmed release permits the next stage.
5. Destination actively attempts connection. Mac checks and selects the CoreAudio output. Android verifies A2DP state, then checks a silent AudioTrack route for up to one second without requesting audio focus or starting a third-party player.
6. Destination reports `result` / `error`; Mac sends `complete` on confirmed success. A2DP success alone is insufficient for a successful destination result.

Deadline: 35 seconds for the transaction; shorter adapter checks fit within it. Cancel, sleep, key revocation, BLE loss and hold discard the transaction. Already issued OS operations may be uncancellable. No automatic rollback is attempted after uncertain outcomes: reconnecting the source could steal a connection the destination successfully established. Recovery is an explicit new request.

## Automatic switching (application version 0.2)

The authenticated wire envelope remains version 1. Update both apps together.

`activity.detail` contains bounded JSON: `available`, `playing`, `call`, `held`, `automation`, monotonic `event`, `source`, `connected`, and optional `guardReason` (0.3.1). Each side sends state on change and at least every three seconds; a peer snapshot older than seven seconds cannot authorize automation. Source names are labels, not track names or playback history.

Mac alone runs the policy and serializes transfers. Android reports debounced starts; `autoStatus` carries the policy reason, paused/ready state, and an armed/blocked gate. Both senders consume playback starts while blocked, including manual priority, calls, missing permissions, stale link and transfer cooldown. Suppressed starts cannot become delayed takeovers when a guard clears. Fresh connections and configuration changes baseline already-running media.

`prepare.detail` is `auto` or `manual`. Both peers revalidate automatic prerequisites before release/acquire. `resumeAuto` explicitly clears the error circuit and coordinator timers. In 0.3.1 the coordinator sends `autoReset` to clear the Android quiet timer and baseline its media edges; neither side replays already-running media. Android awaits a subsequent armed `autoStatus` before generating new starts. Normal post-transfer cooldown is owned by the Mac coordinator. `mode.target` selects `idle` or `follow` at the coordinator. Manual commands remain available subject to device readiness and call/hold guards. No phone contacts, audio recording, telemetry, accessibility service, cloud messages or playback history are read.

The automatic policy is a best-effort coordinator, not a distributed atomic audio transaction. BLE state is sampled, OEM audio operations may be uncancellable, and an OS may deny A2DP control. A successful transfer confirms the route checks described above, not audible playback in every application.

## Application version 0.3

Update both applications. `activity.device` carries the selected headset address and `activity.target` its display name. Empty selection is explicit. Both interfaces show the selections and reject a known mismatch before a transaction; prepare/ready still validate independently. Matching names are never treated as matching identities.

Pairing QR is local UTF-8 JSON: `app: "seamless-headphones"`, `version: 1`, `key: <32-byte Base64>`, `device: <Bluetooth address or empty>`, `name: <display label>`. Android scans offline, validates the format, asks before replacing its key and may select an already paired exact address. It cannot enroll while an existing link runs. No URL is opened and no key is sent over BLE. `protocol/pairing-vector.json` contains synthetic test data.

Optional diagnostics stay in local memory (1000 technical entries per device). Exports contain addresses, OS/build information, packet types, transaction IDs, frame sizes, queue depth, MTU and stage/latency. Keys, QR payloads, full signed frames and camera images are excluded. The devices do not exchange journals; peer-originated results are marked with their source.
