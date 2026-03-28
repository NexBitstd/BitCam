package dev.nexbit.bitcam.clientcommon;

public record EncodedLocalFrame(
    int frameId,
    int width,
    int height,
    long captureTimeMillis,
    boolean keyFrame,
    byte[] payload
) {
}
