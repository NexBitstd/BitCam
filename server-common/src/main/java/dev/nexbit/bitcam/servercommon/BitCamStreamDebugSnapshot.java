package dev.nexbit.bitcam.servercommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;

public record BitCamStreamDebugSnapshot(
    int width,
    int height,
    int frameId,
    int fragmentCount,
    boolean keyFrame,
    int viewerCount,
    long captureAgeMillis,
    long lastPacketAgeMillis,
    BitCamBubbleStyle bubbleStyle
) {
}
