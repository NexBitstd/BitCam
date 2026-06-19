package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import dev.nexbit.bitcam.protocol.udp.BitCamVideoCodec;
import java.util.UUID;

public record RemoteFrame(
    UUID streamerId,
    int frameId,
    int width,
    int height,
    long captureTimeMillis,
    BitCamBubbleStyle bubbleStyle,
    BitCamVideoCodec codec,
    boolean keyFrame,
    byte[] payload,
    long receivedAtMillis
) {
}
