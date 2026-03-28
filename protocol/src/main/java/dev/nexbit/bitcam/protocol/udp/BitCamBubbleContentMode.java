package dev.nexbit.bitcam.protocol.udp;

public enum BitCamBubbleContentMode {
    COVER,
    CONTAIN,
    STRETCH;

    public static BitCamBubbleContentMode fromSerialized(String value) {
        if (value == null || value.isBlank()) {
            return COVER;
        }

        for (BitCamBubbleContentMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }

        return COVER;
    }
}
