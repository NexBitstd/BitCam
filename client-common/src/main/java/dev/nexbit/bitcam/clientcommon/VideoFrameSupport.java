package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleShape;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Shared pixel helpers used by both encoders (scaling) and decoders (post-processing). */
final class VideoFrameSupport {
    // The bubble is drawn small on screen, so the display texture never needs the full stream
    // resolution. Capping the decoded frame here (on the decode thread) keeps the render thread's
    // per-frame NativeImage fill + GPU upload cheap even for a 1080p60 stream — that cost scales with
    // width*height*fps and is what makes high presets stutter for viewers.
    private static final int MAX_DECODED_HEIGHT = 480;

    private VideoFrameSupport() {
    }

    /**
     * Deep copy of a captured frame so it can be handed to another thread. The capture backend's
     * {@link java.awt.image.BufferedImage} is a converter-owned buffer reused on every grab, so the
     * network encode thread must work on its own copy or it would read a half-overwritten frame.
     */
    static BufferedImage copy(BufferedImage source) {
        int type = source.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : source.getType();
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** Scales {@code source} to the requested size, returning it unchanged when already correct. */
    static BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source;
        }

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /**
     * Applies content-mode cropping, the circle mask and ABGR conversion to a freshly decoded image,
     * producing a display-ready {@link DecodedFrame}. Runs on the decode thread, never on render.
     */
    static DecodedFrame toDecodedFrame(
        BufferedImage source,
        int frameId,
        long captureTimeMillis,
        int sourceWidth,
        int sourceHeight,
        BitCamBubbleStyle style
    ) {
        BufferedImage prepared = clampHeight(BitCamBubbleContentLayout.prepareImage(source, style));
        int width = prepared.getWidth();
        int height = prepared.getHeight();
        int[] argb = prepared.getRGB(0, 0, width, height, null, 0, width);
        int[] abgr = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            abgr[i] = argbToAbgr(argb[i]);
        }

        if (style != null && style.shape() == BitCamBubbleShape.CIRCLE) {
            applyCircleMask(abgr, width, height);
        }

        return new DecodedFrame(frameId, captureTimeMillis, sourceWidth, sourceHeight, width, height, abgr, style);
    }

    /** Downscales to {@link #MAX_DECODED_HEIGHT} (preserving aspect) when the frame is taller. */
    private static BufferedImage clampHeight(BufferedImage image) {
        if (image.getHeight() <= MAX_DECODED_HEIGHT) {
            return image;
        }
        int width = Math.max(1, Math.round((float) image.getWidth() * MAX_DECODED_HEIGHT / image.getHeight()));
        return scale(image, width, MAX_DECODED_HEIGHT);
    }

    private static void applyCircleMask(int[] abgr, int width, int height) {
        float radius = Math.min(width, height) * 0.5F;
        float centerX = width * 0.5F;
        float centerY = height * 0.5F;
        float radiusSquared = radius * radius;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float dx = (x + 0.5F) - centerX;
                float dy = (y + 0.5F) - centerY;
                if ((dx * dx) + (dy * dy) > radiusSquared) {
                    int index = (y * width) + x;
                    abgr[index] = abgr[index] & 0x00FFFFFF;
                }
            }
        }
    }

    private static int argbToAbgr(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }
}
