package dev.nexbit.bitcam.clientcommon;

import java.util.function.Consumer;

/**
 * Decodes encoded {@link RemoteFrame}s into display-ready {@link DecodedFrame}s.
 *
 * <p>Decoded frames are delivered to the supplied {@code output} sink rather than returned, because
 * inter-frame codecs are not 1:1: an H.264 decoder only emits a frame once it has seen enough of the
 * following data to delimit it, so {@link #decode} may produce zero, one, or several frames (possibly
 * from a background thread). Intra-only codecs (JPEG) just call the sink once, synchronously.
 *
 * <p>Implementations are stateful per stream, so an instance must only be fed one stream's frames.
 */
interface FrameDecoder extends AutoCloseable {
    void decode(RemoteFrame frame, Consumer<DecodedFrame> output);

    @Override
    default void close() {
    }
}
