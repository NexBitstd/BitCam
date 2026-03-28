package dev.nexbit.bitcam.protocol.udp;

public enum BitCamBubbleShape {
    RECTANGLE,
    SQUARE,
    CIRCLE;

    public static BitCamBubbleShape fromSerialized(String value) {
        if (value == null || value.isBlank()) {
            return RECTANGLE;
        }

        try {
            return BitCamBubbleShape.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return RECTANGLE;
        }
    }
}
