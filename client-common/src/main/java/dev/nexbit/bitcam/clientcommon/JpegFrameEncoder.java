package dev.nexbit.bitcam.clientcommon;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

public final class JpegFrameEncoder {
    public byte[] encode(BufferedImage source, int targetWidth, int targetHeight, float quality) {
        BufferedImage scaled = this.scale(source, targetWidth, targetHeight);

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                throw new IllegalStateException("No JPEG writer is available");
            }

            ImageWriter writer = writers.next();
            try (MemoryCacheImageOutputStream imageOutput = new MemoryCacheImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                ImageWriteParam params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(Math.clamp(quality, 0.1F, 1.0F));
                writer.write(null, new IIOImage(scaled, null, null), params);
            } finally {
                writer.dispose();
            }

            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode webcam frame to JPEG", exception);
        }
    }

    private BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source;
        }

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return scaled;
    }
}
