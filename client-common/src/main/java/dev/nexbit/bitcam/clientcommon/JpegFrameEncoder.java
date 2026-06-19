package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamVideoCodec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Motion-JPEG encoder: each frame is encoded independently, so every frame is a keyframe. */
public final class JpegFrameEncoder implements FrameEncoder {
    private volatile float bitrateScale = 1.0F;

    @Override
    public BitCamVideoCodec codec() {
        return BitCamVideoCodec.MJPEG;
    }

    @Override
    public void setBitrateScale(float scale) {
        this.bitrateScale = Math.clamp(scale, 0.1F, 1.0F);
    }

    @Override
    public EncodedLocalFrame encode(
        BufferedImage source,
        int targetWidth,
        int targetHeight,
        int fps,
        float quality,
        int frameId,
        long captureTimeMillis
    ) {
        BufferedImage scaled = VideoFrameSupport.scale(source, targetWidth, targetHeight);
        // Trim JPEG quality under congestion; MJPEG has no inter-frame state, so this is the only knob.
        byte[] payload = encodeJpeg(scaled, quality * this.bitrateScale);
        return new EncodedLocalFrame(frameId, targetWidth, targetHeight, captureTimeMillis, true, BitCamVideoCodec.MJPEG, payload);
    }

    private static byte[] encodeJpeg(BufferedImage scaled, float quality) {
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
}
