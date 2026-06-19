package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/**
 * Motion-JPEG decoder: every frame is a self-contained JPEG, so decoding is stateless, synchronous
 * and any frame can be decoded independently.
 */
final class JpegFrameDecoder implements FrameDecoder {
    @Override
    public void decode(RemoteFrame frame, Consumer<DecodedFrame> output) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(frame.payload())) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                return;
            }
            output.accept(VideoFrameSupport.toDecodedFrame(
                image,
                frame.frameId(),
                frame.captureTimeMillis(),
                frame.width(),
                frame.height(),
                frame.bubbleStyle()
            ));
        } catch (IOException ignored) {
            // A corrupt JPEG just means a dropped frame; the next one is independent.
        }
    }
}
