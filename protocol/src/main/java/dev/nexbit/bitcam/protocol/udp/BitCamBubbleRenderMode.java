package dev.nexbit.bitcam.protocol.udp;

public enum BitCamBubbleRenderMode {
    BILLBOARD,
    VERTICAL_BILLBOARD,
    HORIZONTAL_BILLBOARD,
    HEAD_LOCKED;

    public static BitCamBubbleRenderMode fromSerialized(String value) {
        if (value == null || value.isBlank()) {
            return BILLBOARD;
        }

        try {
            return BitCamBubbleRenderMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return BILLBOARD;
        }
    }
}
