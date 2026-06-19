package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamVideoCodec;
import java.awt.image.BufferedImage;

/**
 * Encodes captured camera frames for transmission.
 *
 * <p>{@link #encode} may return {@code null} when the codec buffered this frame and has nothing to
 * send yet (some inter-frame encoders); callers should simply skip it.
 */
interface FrameEncoder extends AutoCloseable {
    EncodedLocalFrame encode(
        BufferedImage source,
        int targetWidth,
        int targetHeight,
        int fps,
        float quality,
        int frameId,
        long captureTimeMillis
    );

    BitCamVideoCodec codec();

    /** Asks the encoder to emit a keyframe as soon as possible. No-op for intra-only codecs. */
    default void requestKeyframe() {
    }

    /**
     * Sets a congestion-driven bitrate multiplier in [0,1] that the encoder applies to its target
     * bitrate (1.0 = full quality). Called frequently; implementations should ignore tiny changes.
     */
    default void setBitrateScale(float scale) {
    }

    @Override
    default void close() {
    }
}
