package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * H.264 decoder built on {@link FFmpegFrameGrabber}.
 *
 * <p>The grabber is pull-based and an Annex-B parser cannot emit a frame until it sees the start of
 * the next one, so decoding is inherently not 1:1: received bytes are pushed into a blocking stream
 * and a background grab thread emits decoded frames (one behind). Each decoded image is paired with
 * the metadata of the frame fed in the same order via a FIFO.
 *
 * <p>Decoding only starts once a keyframe has been seen, so a viewer that joins mid-stream waits for
 * the next IDR rather than feeding the decoder undecodable inter-frames.
 *
 * <p><b>Experimental:</b> needs in-game validation on each OS.
 */
final class H264FrameDecoder implements FrameDecoder {
    private final BlockingInputStream input = new BlockingInputStream();
    private final ConcurrentLinkedQueue<FrameMeta> pendingMeta = new ConcurrentLinkedQueue<>();
    private final Consumer<Throwable> failureConsumer;
    private volatile Consumer<DecodedFrame> output;
    private volatile boolean closed;
    private volatile boolean failed;
    private boolean keyFrameSeen;
    private boolean started;
    private Thread grabThread;

    H264FrameDecoder(Consumer<Throwable> failureConsumer) {
        this.failureConsumer = failureConsumer == null ? ignored -> {
        } : failureConsumer;
    }

    @Override
    public void decode(RemoteFrame frame, Consumer<DecodedFrame> output) {
        if (this.closed) {
            return;
        }
        if (this.failed) {
            return;
        }
        if (!this.keyFrameSeen) {
            if (!frame.keyFrame()) {
                // Can't start an H.264 decoder mid-GOP — wait for the first keyframe.
                return;
            }
            this.keyFrameSeen = true;
        }

        this.output = output;
        this.pendingMeta.add(new FrameMeta(frame.frameId(), frame.captureTimeMillis(), frame.width(), frame.height(), frame.bubbleStyle()));
        this.input.write(frame.payload());

        if (!this.started) {
            this.started = true;
            this.grabThread = new Thread(this::runGrabLoop, "bitcam-h264-decode");
            this.grabThread.setDaemon(true);
            this.grabThread.start();
        }
    }

    private void runGrabLoop() {
        CameraLibraryManager.applyToThread();
        FFmpegFrameGrabber grabber = null;
        Java2DFrameConverter converter = new Java2DFrameConverter();
        try {
            FFmpegFrameGrabber.tryLoad();
            CameraLibraryManager.configureFfmpegLoggingAfterLoad();
            grabber = new FFmpegFrameGrabber(this.input);
            grabber.setFormat("h264");
            grabber.start();
            while (!this.closed) {
                Frame frame = grabber.grabImage();
                if (frame == null) {
                    if (this.closed) {
                        break;
                    }
                    continue;
                }
                BufferedImage image = converter.convert(frame);
                FrameMeta meta = this.pendingMeta.poll();
                Consumer<DecodedFrame> sink = this.output;
                if (image == null || meta == null || sink == null) {
                    continue;
                }
                sink.accept(VideoFrameSupport.toDecodedFrame(
                    image, meta.frameId(), meta.captureTimeMillis(), meta.sourceWidth(), meta.sourceHeight(), meta.bubbleStyle()
                ));
            }
        } catch (Throwable exception) {
            if (!this.closed) {
                this.failed = true;
                this.failureConsumer.accept(exception);
            }
            // A grabber failure ends decoding; the stream is rebuilt on the next keyframe.
        } finally {
            if (grabber != null) {
                try {
                    grabber.close();
                } catch (Exception ignored) {
                    // Best-effort.
                }
            }
            converter.close();
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.input.close();
        if (this.grabThread != null) {
            this.grabThread.interrupt();
        }
    }

    boolean failed() {
        return this.failed;
    }

    private record FrameMeta(int frameId, long captureTimeMillis, int sourceWidth, int sourceHeight, BitCamBubbleStyle bubbleStyle) {
    }

    /** Blocking {@link InputStream} backed by a bounded queue of byte chunks fed by the network. */
    private static final class BlockingInputStream extends InputStream {
        private final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(64);
        private byte[] current = new byte[0];
        private int position;
        private volatile boolean closed;

        void write(byte[] data) {
            if (this.closed || data.length == 0) {
                return;
            }
            // Drop on overflow rather than block the network thread; a gap just forces a keyframe wait.
            this.chunks.offer(data);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = this.read(one, 0, 1);
            return read < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (this.position >= this.current.length) {
                if (this.closed) {
                    return -1;
                }
                try {
                    byte[] next = this.chunks.poll(200, TimeUnit.MILLISECONDS);
                    if (next != null) {
                        this.current = next;
                        this.position = 0;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            int toCopy = Math.min(length, this.current.length - this.position);
            System.arraycopy(this.current, this.position, buffer, offset, toCopy);
            this.position += toCopy;
            return toCopy;
        }

        @Override
        public void close() {
            this.closed = true;
            this.chunks.clear();
        }
    }
}
