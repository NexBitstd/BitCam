package dev.nexbit.bitcam.clientcommon;

public record LocalPreviewFrame(
    int frameId,
    int width,
    int height,
    long captureTimeMillis,
    byte[] payload,
    long receivedAtMillis
) {
}
