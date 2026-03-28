# Webcam Implementation Plan

## Goal

Build a webcam-streaming stack that mirrors the overall shape of Plasmo Voice:

- signaling over Minecraft packets
- media transport over UDP
- server-authoritative routing
- Fabric + NeoForge clients
- Fabric + NeoForge + Paper servers
- shared protocol/common layers to reduce future porting cost

## Current status

Implemented:

- root Gradle version matrix
  - auto-discovery of `versions/*/version.properties`
  - per-version loader metadata instead of single active root version block
  - shared loader build scripts in `versions/shared/*.gradle.kts`
  - pluginManagement prepared for the same Essential multi-version toolchain family used by Plasmo Voice
  - shared IntelliJ `.run` configs for Fabric/NeoForge/Paper dev runs on `1.21.8`
- `protocol`
  - binary control codec
  - UDP packet codec
  - session handshake packets
- `client-common`
  - webcam discovery via `webcam-capture`
  - backend abstraction for camera capture
  - JavaCV/OpenCV camera backend for macOS
  - `webcam-capture` fallback backend for non-macOS platforms
  - preferred camera config
  - JPEG frame encoding
  - UDP client
  - remote frame reassembly/store
  - safe camera-backend fallback so Apple Silicon/macOS does not crash when opening settings
- `server-common`
  - server config file
  - session registry
  - UDP server
  - proximity-based fan-out
- Fabric
  - custom payload signaling
  - client join/disconnect lifecycle
  - `V` keybind
  - `/bitcam` client commands
  - billboard rendering above players
- NeoForge
  - bidirectional optional payload registration
  - client join/disconnect lifecycle
  - `V` keybind
  - `/bitcam` client commands
  - billboard rendering above players
- Paper
  - plugin message signaling on `bitcam:control`
  - shared UDP media server

Verified:

- `./gradlew printVersionMatrix`
- `./gradlew :mc1_21_8:fabric:build`
- `./gradlew :mc1_21_8:neoforge:build`
- `./gradlew :mc1_21_8:paper:build`
- `./gradlew build`

## Implementation order

1. `protocol`
   - binary codecs for signaling and UDP media packets
   - protocol versioning
   - session auth token handling

2. `server-common`
   - UDP socket listener
   - pending and active session registry
   - viewer resolution hooks
   - stream forwarding to nearby viewers

3. `client-common`
   - webcam discovery and selection
   - frame capture
   - frame encoding and fragmentation
   - UDP client and frame reassembly
   - remote frame cache for renderers

4. Loader integrations
   - Fabric: full first vertical slice
   - NeoForge: same signal/media flow via its payload APIs
   - Paper: server-side signaling via plugin channels and shared UDP server

5. Rendering and UX
  - billboard render above player heads
  - camera selection commands/config
  - toggle streaming keybind

## Current user flow

1. Client joins the server.
2. Client sends `ClientHello` over `bitcam:control`.
3. Server creates a session secret and replies with `ServerWelcome`.
4. Client opens UDP connection to the advertised endpoint.
5. Client streams JPEG frames over fragmented UDP packets.
6. Server forwards fragments only to nearby subscribed viewers.
7. Viewer client reassembles frames and renders them above the streamer.

## Current transport choice

The first implementation uses:

- JPEG intra-frames over UDP
- MTU-safe fragmentation
- session auth through server-issued secret
- server-side proximity fan-out

This is not the final codec strategy. A later evolution can replace JPEG with H.264 or another low-latency codec without changing the signaling/session shape.

## Operational note

- `udp.host` in generated `bitcam-server.properties` is the advertised host sent to clients.
- Default `127.0.0.1` is only suitable for localhost testing.
- On any real server this must be changed to a reachable public IP or hostname.

## Constraints

- true realtime lossless video is not bandwidth-practical for public Minecraft servers
- initial implementation targets visually-lossless-at-billboard-size quality
- clients connect to one server UDP endpoint; no direct client-to-client media transport
- Paper remains server-only
- current macOS camera path uses JavaCV/OpenCV; `webcam-capture` remains only as a fallback backend, because its default BridJ/OpenIMAJ path is not safe on Apple Silicon

## Next technical upgrades

- keyframe requests
- adaptive bitrate/FPS
- partial frame drop under pressure
- stream permissions and moderation
- optional encryption for media payloads
- codec abstraction for H.264/other low-latency encoders
- package/distribution strategy for JavaCV native runtime on client releases
- integration tests across Fabric/NeoForge/Paper combinations
