package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;

public record CapturedCameraFrame(int width, int height, byte[] rgbaPixels) {
    public CapturedCameraFrame {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Camera frame dimensions must be positive");
        }
        if (rgbaPixels == null || rgbaPixels.length != width * height * 4) {
            throw new IllegalArgumentException("RGBA frame length must be width * height * 4");
        }
    }

    public static CapturedCameraFrame fromBufferedImage(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage scaled = VideoFrameSupport.scale(source, targetWidth, targetHeight);
        int[] argb = scaled.getRGB(0, 0, targetWidth, targetHeight, null, 0, targetWidth);
        byte[] rgba = new byte[targetWidth * targetHeight * 4];
        for (int i = 0, p = 0; i < argb.length; i++, p += 4) {
            int color = argb[i];
            rgba[p] = (byte) ((color >> 16) & 0xFF);
            rgba[p + 1] = (byte) ((color >> 8) & 0xFF);
            rgba[p + 2] = (byte) (color & 0xFF);
            rgba[p + 3] = (byte) ((color >> 24) & 0xFF);
        }
        return new CapturedCameraFrame(targetWidth, targetHeight, rgba);
    }

    public BufferedImage toBufferedImage() {
        BufferedImage image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[this.width * this.height];
        for (int i = 0, p = 0; i < argb.length; i++, p += 4) {
            int red = this.rgbaPixels[p] & 0xFF;
            int green = this.rgbaPixels[p + 1] & 0xFF;
            int blue = this.rgbaPixels[p + 2] & 0xFF;
            int alpha = this.rgbaPixels[p + 3] & 0xFF;
            argb[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
        image.setRGB(0, 0, this.width, this.height, argb, 0, this.width);
        return image;
    }
}
