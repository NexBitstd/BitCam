# BitCam

**BitCam** adds proximity-based camera video chat to Minecraft.

Players can turn on their camera and stream video to nearby players in-game. The video is rendered above players as a small camera billboard, making multiplayer interactions feel more alive while keeping the experience lightweight and proximity-focused.

![BitCam preview](TODO_ADD_SCREENSHOT_URL)

## Features

- Proximity-based video streaming
- Camera toggle keybind: `V`
- In-game camera selection commands
- Dedicated UDP media transport
- Fabric and NeoForge client support
- Fabric, NeoForge, and Paper server support
- Server-side configuration for UDP host, port, range, and stream limits

## Commands

```text
/bitcam toggle
/bitcam cameras
/bitcam camera <index>
```

## Supported Platforms

| Platform | Client | Server |
| --- | --- | --- |
| Fabric | Yes | Yes |
| NeoForge | Yes | Yes |
| Paper | No | Yes |

## Screenshots

![Camera enabled in-game](TODO_ADD_SCREENSHOT_URL)

![Nearby player video preview](TODO_ADD_SCREENSHOT_URL)

## Setup Notes

BitCam uses a normal Minecraft signaling channel plus a separate UDP socket for video packets.

For local testing, the default UDP host is usually enough. For dedicated servers, set `udp.host` in `bitcam-server.properties` to your public server IP or hostname.

## Status

BitCam is currently an early baseline implementation.

The video path uses JPEG over UDP and is intended to improve over time.
