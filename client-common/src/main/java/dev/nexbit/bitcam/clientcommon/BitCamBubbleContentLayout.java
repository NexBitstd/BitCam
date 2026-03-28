package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleContentMode;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleShape;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public final class BitCamBubbleContentLayout {
    private BitCamBubbleContentLayout() {
    }

    public static float displayAspectRatio(int width, int height, BitCamBubbleStyle style) {
        BitCamBubbleShape shape = style == null ? BitCamBubbleShape.RECTANGLE : style.shape();
        if (shape == BitCamBubbleShape.CIRCLE || shape == BitCamBubbleShape.SQUARE) {
            return 1.0F;
        }

        return height == 0 ? 1.0F : (float) width / height;
    }

    public static UvCrop computeUvCrop(int width, int height, BitCamBubbleStyle style) {
        if (width <= 0 || height <= 0) {
            return UvCrop.FULL;
        }

        BitCamBubbleShape shape = style == null ? BitCamBubbleShape.RECTANGLE : style.shape();
        BitCamBubbleContentMode contentMode = style == null ? BitCamBubbleContentMode.COVER : style.contentMode();
        float sourceAspect = height == 0 ? 1.0F : (float) width / height;
        float targetAspect = switch (contentMode) {
            case COVER -> (shape == BitCamBubbleShape.CIRCLE || shape == BitCamBubbleShape.SQUARE) ? 1.0F : sourceAspect;
            case CONTAIN, STRETCH -> sourceAspect;
        };

        float zoom = Math.max(1.0F, (style == null ? 100 : style.contentZoomPercent()) / 100.0F);
        float visibleWidth;
        float visibleHeight;
        if (sourceAspect > targetAspect) {
            visibleHeight = 1.0F / zoom;
            visibleWidth = visibleHeight * (targetAspect / sourceAspect);
        } else if (sourceAspect < targetAspect) {
            visibleWidth = 1.0F / zoom;
            visibleHeight = visibleWidth * (sourceAspect / targetAspect);
        } else {
            visibleWidth = 1.0F / zoom;
            visibleHeight = 1.0F / zoom;
        }

        float horizontalSlack = Math.max(0.0F, 1.0F - visibleWidth);
        float verticalSlack = Math.max(0.0F, 1.0F - visibleHeight);
        float horizontalOffset = horizontalSlack * normalizedOffset(style == null ? 100 : style.contentXOffsetPercent());
        float verticalOffset = verticalSlack * normalizedOffset(style == null ? 100 : style.contentYOffsetPercent());

        return new UvCrop(horizontalOffset, verticalOffset, horizontalOffset + visibleWidth, verticalOffset + visibleHeight);
    }

    public static BufferedImage prepareImage(BufferedImage source, BitCamBubbleStyle style) {
        if (source == null) {
            return null;
        }

        BufferedImage cropped = cropSource(source, style);
        BitCamBubbleContentMode contentMode = style == null ? BitCamBubbleContentMode.COVER : style.contentMode();
        if (contentMode != BitCamBubbleContentMode.CONTAIN) {
            return cropped;
        }

        float targetAspect = displayAspectRatio(source.getWidth(), source.getHeight(), style);
        return padToAspect(cropped, targetAspect);
    }

    private static BufferedImage cropSource(BufferedImage source, BitCamBubbleStyle style) {
        UvCrop crop = computeUvCrop(source.getWidth(), source.getHeight(), style);
        int cropX = Math.max(0, Math.min(source.getWidth() - 1, Math.round(crop.u0() * source.getWidth())));
        int cropY = Math.max(0, Math.min(source.getHeight() - 1, Math.round(crop.v0() * source.getHeight())));
        int cropWidth = Math.max(1, Math.min(source.getWidth() - cropX, Math.round((crop.u1() - crop.u0()) * source.getWidth())));
        int cropHeight = Math.max(1, Math.min(source.getHeight() - cropY, Math.round((crop.v1() - crop.v0()) * source.getHeight())));

        if (cropX == 0 && cropY == 0 && cropWidth == source.getWidth() && cropHeight == source.getHeight()) {
            return source;
        }

        BufferedImage cropped = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = cropped.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, cropWidth, cropHeight, cropX, cropY, cropX + cropWidth, cropY + cropHeight, null);
        } finally {
            graphics.dispose();
        }
        return cropped;
    }

    private static BufferedImage padToAspect(BufferedImage source, float targetAspect) {
        if (source == null || targetAspect <= 0.0F) {
            return source;
        }

        float sourceAspect = source.getHeight() == 0 ? 1.0F : (float) source.getWidth() / source.getHeight();
        if (Math.abs(sourceAspect - targetAspect) < 0.01F) {
            return source;
        }

        int targetWidth = source.getWidth();
        int targetHeight = source.getHeight();
        if (sourceAspect > targetAspect) {
            targetHeight = Math.max(1, Math.round(source.getWidth() / targetAspect));
        } else {
            targetWidth = Math.max(1, Math.round(source.getHeight() * targetAspect));
        }

        BufferedImage padded = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        int x = (targetWidth - source.getWidth()) / 2;
        int y = (targetHeight - source.getHeight()) / 2;
        Graphics2D graphics = padded.createGraphics();
        try {
            graphics.drawImage(source, x, y, null);
        } finally {
            graphics.dispose();
        }
        return padded;
    }

    private static float normalizedOffset(int percent) {
        return Math.max(0.0F, Math.min(1.0F, percent / 200.0F));
    }

    public record UvCrop(float u0, float v0, float u1, float v1) {
        public static final UvCrop FULL = new UvCrop(0.0F, 0.0F, 1.0F, 1.0F);
    }
}
