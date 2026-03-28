package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.util.UUID;

public record RemoteFrame(
    UUID streamerId,
    int frameId,
    int width,
    int height,
    long captureTimeMillis,
    BitCamBubbleStyle bubbleStyle,
    byte[] payload,
    long receivedAtMillis
) {
}
