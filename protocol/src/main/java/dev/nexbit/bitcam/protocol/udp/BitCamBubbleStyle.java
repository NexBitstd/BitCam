package dev.nexbit.bitcam.protocol.udp;

public record BitCamBubbleStyle(
    BitCamBubblePreset preset,
    BitCamBubbleShape shape,
    BitCamBubbleRenderMode renderMode,
    int scalePercent,
    int xOffsetPercent,
    int yOffsetPercent,
    int opacityPercent,
    BitCamBubbleContentMode contentMode,
    int contentZoomPercent,
    int contentXOffsetPercent,
    int contentYOffsetPercent
) {
    public static final int BUBBLE_OFFSET_MAX = 500;

    public static final BitCamBubbleStyle DEFAULT = new BitCamBubbleStyle(
        BitCamBubblePreset.CLASSIC,
        BitCamBubbleShape.RECTANGLE,
        BitCamBubbleRenderMode.BILLBOARD,
        100,
        0,
        0,
        94,
        BitCamBubbleContentMode.COVER,
        100,
        100,
        100
    );

    public BitCamBubbleStyle {
        preset = preset == null ? BitCamBubblePreset.CLASSIC : preset;
        shape = shape == null ? BitCamBubbleShape.RECTANGLE : shape;
        renderMode = renderMode == null ? BitCamBubbleRenderMode.BILLBOARD : renderMode;
        contentMode = contentMode == null ? BitCamBubbleContentMode.COVER : contentMode;
        scalePercent = clamp(scalePercent, 60, 180);
        xOffsetPercent = clamp(xOffsetPercent, -BUBBLE_OFFSET_MAX, BUBBLE_OFFSET_MAX);
        yOffsetPercent = clamp(yOffsetPercent, -BUBBLE_OFFSET_MAX, BUBBLE_OFFSET_MAX);
        opacityPercent = clamp(opacityPercent, 35, 100);
        contentZoomPercent = clamp(contentZoomPercent, 100, 250);
        contentXOffsetPercent = clamp(contentXOffsetPercent, 0, 200);
        contentYOffsetPercent = clamp(contentYOffsetPercent, 0, 200);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public BitCamBubbleStyle withPreset(BitCamBubblePreset value) {
        return new BitCamBubbleStyle(
            value,
            this.shape,
            this.renderMode,
            this.scalePercent,
            this.xOffsetPercent,
            this.yOffsetPercent,
            this.opacityPercent,
            this.contentMode,
            this.contentZoomPercent,
            this.contentXOffsetPercent,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withShape(BitCamBubbleShape value) {
        return new BitCamBubbleStyle(
            this.preset,
            value,
            this.renderMode,
            this.scalePercent,
            this.xOffsetPercent,
            this.yOffsetPercent,
            this.opacityPercent,
            this.contentMode,
            this.contentZoomPercent,
            this.contentXOffsetPercent,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withRenderMode(BitCamBubbleRenderMode value) {
        return new BitCamBubbleStyle(
            this.preset,
            this.shape,
            value,
            this.scalePercent,
            this.xOffsetPercent,
            this.yOffsetPercent,
            this.opacityPercent,
            this.contentMode,
            this.contentZoomPercent,
            this.contentXOffsetPercent,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withScalePercent(int value) {
        return new BitCamBubbleStyle(
            this.preset,
            this.shape,
            this.renderMode,
            value,
            this.xOffsetPercent,
            this.yOffsetPercent,
            this.opacityPercent,
            this.contentMode,
            this.contentZoomPercent,
            this.contentXOffsetPercent,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withXOffsetPercent(int value) {
        return new BitCamBubbleStyle(
            this.preset,
            this.shape,
            this.renderMode,
            this.scalePercent,
            value,
            this.yOffsetPercent,
            this.opacityPercent,
            this.contentMode,
            this.contentZoomPercent,
            this.contentXOffsetPercent,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withYOffsetPercent(int value) {
        return new BitCamBubbleStyle(
            this.preset,
            this.shape,
            this.renderMode,
            this.scalePercent,
            this.xOffsetPercent,
            value,
            this.opacityPercent,
            this.contentMode,
            this.contentZoomPercent,
            this.contentXOffsetPercent,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withOpacityPercent(int value) {
        return new BitCamBubbleStyle(
            this.preset,
            this.shape,
            this.renderMode,
            this.scalePercent,
            this.xOffsetPercent,
            this.yOffsetPercent,
            value,
            this.contentMode,
            this.contentZoomPercent,
            this.contentXOffsetPercent,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withContentMode(BitCamBubbleContentMode value) {
        return new BitCamBubbleStyle(
            this.preset,
            this.shape,
            this.renderMode,
            this.scalePercent,
            this.xOffsetPercent,
            this.yOffsetPercent,
            this.opacityPercent,
            value,
            this.contentZoomPercent,
            this.contentXOffsetPercent,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withContentZoomPercent(int value) {
        return new BitCamBubbleStyle(
            this.preset,
            this.shape,
            this.renderMode,
            this.scalePercent,
            this.xOffsetPercent,
            this.yOffsetPercent,
            this.opacityPercent,
            this.contentMode,
            value,
            this.contentXOffsetPercent,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withContentXOffsetPercent(int value) {
        return new BitCamBubbleStyle(
            this.preset,
            this.shape,
            this.renderMode,
            this.scalePercent,
            this.xOffsetPercent,
            this.yOffsetPercent,
            this.opacityPercent,
            this.contentMode,
            this.contentZoomPercent,
            value,
            this.contentYOffsetPercent
        );
    }

    public BitCamBubbleStyle withContentYOffsetPercent(int value) {
        return new BitCamBubbleStyle(
            this.preset,
            this.shape,
            this.renderMode,
            this.scalePercent,
            this.xOffsetPercent,
            this.yOffsetPercent,
            this.opacityPercent,
            this.contentMode,
            this.contentZoomPercent,
            this.contentXOffsetPercent,
            value
        );
    }
}
