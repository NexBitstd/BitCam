package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamVideoCodec;

public record EncodedLocalFrame(
    int frameId,
    int width,
    int height,
    long captureTimeMillis,
    boolean keyFrame,
    BitCamVideoCodec codec,
    byte[] payload
) {
}
