# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BitCam is a multi-platform Minecraft mod that streams a live webcam feed above a player's character in-game. It supports Fabric, NeoForge, and Paper loaders with a shared protocol and media stack.

## Build Commands

```bash
# Build all modules + current MC version (1.21.8)
./gradlew buildCurrentVersion

# Build only a specific loader
./gradlew :mc1_21_8:fabric:build
./gradlew :mc1_21_8:neoforge:build
./gradlew :mc1_21_8:paper:build

# Build everything across all discovered versions/loaders
./gradlew buildVersionMatrix

# Print discovered version matrix
./gradlew printVersionMatrix
```

There are IntelliJ run configurations in `.run/` for Fabric/NeoForge client+server and Paper server at 1.21.8.

There are no automated tests in this codebase currently.

## Architecture

### Module Layout

```
common/          → Platform abstractions (PlatformAccess, BitCamBootstrap, loader/distribution enums)
protocol/        → Binary protocol definitions (signal packets + UDP packet types)
client-common/   → Shared client stack (camera capture, JPEG encoding, UDP client, frame stores)
server-common/   → Shared server stack (UDP server, session management, quality presets, viewer routing)
versions/
  1.21.8/
    fabric/      → Fabric-specific bootstrap, rendering, networking, key bindings
    neoforge/    → NeoForge-specific bootstrap, rendering, networking
    paper/       → Paper plugin bootstrap, server-only
  shared/
    fabric.gradle.kts    → Shared Fabric build config (applied to all fabric versions)
    neoforge.gradle.kts  → Shared NeoForge build config
    paper.gradle.kts     → Shared Paper build config
    src/client/          → Shared client source tree included in both Fabric and NeoForge
buildSrc/        → Custom Gradle plugin: BitCamVersionMatrix (auto-discovers versions/)
```

**Key constraint:** `paper/` is server-only — never add client-side code there. `client-common/` is never included in Paper builds.

### Ports & Adapters Pattern

- `common/` defines interfaces (`PlatformAccess`, `BitCamPlayerPermissionChecker`, `BitCamViewerResolver`)
- Loader modules implement these interfaces and wire everything together
- New MC version support = create `versions/X.Y.Z/version.properties` and the build system auto-discovers it

### Protocol Flow (Handshake → Stream)

1. **Signal (MC networking, channel `bitcam:control`):**
   - Client → Server: `ClientHelloSignalPacket` (protocol version + requested quality profile)
   - Server → Client: `ServerWelcomeSignalPacket` (UDP endpoint, session UUID + 16-byte secret, video dimensions, available quality profiles)

2. **UDP (direct socket):**
   - Client → Server: `SessionRequestUdpPacket` (sessionId + secret for auth)
   - Server → Client: `SessionAcceptedUdpPacket`
   - Client → Server: `VideoFrameUdpPacket` (fragmented JPEG frames) + `KeepAliveUdpPacket`
   - Server routes frames to nearby players via `BitCamViewerResolver`

### Key Classes

| Class | Module | Role |
|---|---|---|
| `BitCamClientCoordinator` | client-common | Client state machine: camera selection, stream lifecycle, UDP client |
| `BitCamServerCoordinator` | server-common | Server state: session registry, welcome packet generation, quality filtering |
| `BitCamUdpClient` | client-common | Sends JPEG frames and keep-alives over UDP |
| `BitCamUdpServer` | server-common | Receives frames, validates session secrets, routes to viewers |
| `BitCamSignalCodec` | protocol | Binary encode/decode for signal packets |
| `BitCamSession` | server-common | Per-player session (UUID, secret, UDP address, timeouts) |
| `BitCamServerQualityPreset` | server-common | Quality profile with optional permission expression (e.g. `op:2`) |
| `BitCamBootstrap` | common | Called by each loader on init: `bootstrapCommon`, `bootstrapClient`, `bootstrapServer` |

### Configuration

- **Server:** `bitcam-server.properties` — UDP host/port, stream radius, MTU, quality presets with permission expressions
- **Client:** `bitcam-client.properties` — preferred camera, capture mode, quality profile, bubble style
- **`gradle.properties`:** Protocol version (`bitcam_protocol_version`), UDP defaults, stream defaults, frame dimensions

### Version Discovery

`settings.gradle.kts` auto-scans `versions/*/version.properties` and includes those subprojects. `buildSrc/` provides `discoverBitCamVersions()` and `BitCamVersionMatrix`. MC versions map to project prefixes: `1.21.8` → `:mc1_21_8`.

### Rendering

Billboard rendering (`BitCamBillboardRenderer`) is duplicated in Fabric and NeoForge — changes to rendering logic must be applied to both. Shared client sources live in `versions/shared/src/client/` and are included in both loaders via their respective `*.gradle.kts`.