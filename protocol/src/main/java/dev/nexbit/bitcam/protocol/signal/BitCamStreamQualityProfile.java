package dev.nexbit.bitcam.protocol.signal;

public record BitCamStreamQualityProfile(
    String id,
    String displayName,
    int width,
    int height,
    int fps,
    float quality
) {
    public BitCamStreamQualityProfile {
        id = id == null || id.isBlank() ? "default" : id.trim();
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        width = Math.max(16, width);
        height = Math.max(16, height);
        fps = Math.max(1, fps);
        quality = Math.clamp(quality, 0.1F, 1.0F);
    }
}
