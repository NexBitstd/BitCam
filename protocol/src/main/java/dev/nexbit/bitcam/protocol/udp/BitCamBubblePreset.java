package dev.nexbit.bitcam.protocol.udp;

public enum BitCamBubblePreset {
    CLASSIC,
    MATTE,
    GLASS,
    NEON;

    public static BitCamBubblePreset fromSerialized(String value) {
        if (value == null || value.isBlank()) {
            return CLASSIC;
        }

        try {
            return BitCamBubblePreset.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return CLASSIC;
        }
    }
}
