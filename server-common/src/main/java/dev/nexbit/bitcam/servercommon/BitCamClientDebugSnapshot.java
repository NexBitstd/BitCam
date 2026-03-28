package dev.nexbit.bitcam.servercommon;

import java.util.UUID;

public record BitCamClientDebugSnapshot(
    UUID playerId,
    UUID sessionId,
    boolean sendEnabled,
    boolean receiveEnabled,
    String address,
    long lastSeenAgeMillis,
    BitCamStreamDebugSnapshot stream
) {
}
