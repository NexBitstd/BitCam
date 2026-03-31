package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;

public final class BitCamBubblePlacement {
    private BitCamBubblePlacement() {
    }

    public static float scale(BitCamBubbleStyle bubbleStyle) {
        return bubbleStyle.scalePercent() / 100.0F;
    }

    public static double horizontalOffset(BitCamBubbleStyle bubbleStyle) {
        return horizontalOffset(bubbleStyle, scale(bubbleStyle));
    }

    public static double horizontalOffset(BitCamBubbleStyle bubbleStyle, float scale) {
        double normalizedOffset = bubbleStyle.xOffsetPercent() / (double) BitCamBubbleStyle.BUBBLE_OFFSET_MAX;
        return normalizedOffset * (0.48D + (0.10D * scale));
    }

    public static double worldVerticalOffset(BitCamBubbleStyle bubbleStyle) {
        return worldVerticalOffset(bubbleStyle, scale(bubbleStyle));
    }

    public static double worldVerticalOffset(BitCamBubbleStyle bubbleStyle, float scale) {
        double normalizedOffset = bubbleStyle.yOffsetPercent() / (double) BitCamBubbleStyle.BUBBLE_OFFSET_MAX;
        return (0.26D + (0.14D * scale)) + (normalizedOffset * (1.10D + (0.20D * scale)));
    }

    public static double headPivotVerticalOffset(BitCamBubbleStyle bubbleStyle) {
        return headPivotVerticalOffset(bubbleStyle, scale(bubbleStyle));
    }

    public static double headPivotVerticalOffset(BitCamBubbleStyle bubbleStyle, float scale) {
        return 0.30D + worldVerticalOffset(bubbleStyle, scale);
    }

    public static double firstPersonVerticalOffset(BitCamBubbleStyle bubbleStyle) {
        return firstPersonVerticalOffset(bubbleStyle, scale(bubbleStyle));
    }

    public static double firstPersonVerticalOffset(BitCamBubbleStyle bubbleStyle, float scale) {
        double normalizedOffset = bubbleStyle.yOffsetPercent() / (double) BitCamBubbleStyle.BUBBLE_OFFSET_MAX;
        return (0.28D + (0.14D * scale)) + (normalizedOffset * (0.82D + (0.16D * scale)));
    }
}
