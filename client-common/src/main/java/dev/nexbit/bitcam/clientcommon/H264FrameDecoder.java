package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.javah264.DecodeResult;
import dev.nexbit.javah264.H264Decoder;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Push-based OpenH264 decoder backed by javah264.
 *
 * <p>The transport already reassembles one complete Annex-B access unit per {@link RemoteFrame}; this
 * decoder splits that AU into NAL units and feeds them directly to OpenH264. No container probing or
 * stream buffering is involved, which keeps join and recovery latency low.
 */
final class H264FrameDecoder implements FrameDecoder {
    private final Object lock = new Object();
    private final Consumer<Throwable> failureConsumer;
    private final Consumer<String> lifecycleLog;

    private H264Decoder decoder;
    private boolean keyFrameSeen;
    private boolean firstFrameLogged;
    private volatile boolean failed;
    private volatile boolean closed;

    H264FrameDecoder(Consumer<Throwable> failureConsumer, Consumer<String> lifecycleLog) {
        this.failureConsumer = failureConsumer == null ? ignored -> {
        } : failureConsumer;
        this.lifecycleLog = lifecycleLog == null ? ignored -> {
        } : lifecycleLog;
    }

    @Override
    public void decode(RemoteFrame frame, Consumer<DecodedFrame> output) {
        synchronized (this.lock) {
            if (this.closed || this.failed) {
                return;
            }
            if (!this.keyFrameSeen) {
                if (!frame.keyFrame()) {
                    // Can't start an H.264 decoder mid-GOP — wait for the first keyframe.
                    return;
                }
                this.keyFrameSeen = true;
                this.lifecycleLog.accept("received first keyframe (" + frame.width() + "x" + frame.height() + "), starting decoder");
            }

            try {
                this.ensureDecoder();
                for (byte[] nalUnit : H264Decoder.nalUnits(frame.payload())) {
                    DecodeResult result = this.decoder.decodeRGBA(nalUnit);
                    if (result == null) {
                        continue;
                    }
                    BufferedImage image = rgbaToImage(result.getImage(), result.getWidth(), result.getHeight());
                    output.accept(VideoFrameSupport.toDecodedFrame(
                        image, frame.frameId(), frame.captureTimeMillis(), frame.width(), frame.height(), frame.bubbleStyle()
                    ));
                    if (!this.firstFrameLogged) {
                        this.firstFrameLogged = true;
                        this.lifecycleLog.accept("decoded first frame " + result.getWidth() + "x" + result.getHeight());
                    }
                }
            } catch (Throwable exception) {
                if (!this.closed) {
                    this.failed = true;
                    this.failureConsumer.accept(exception);
                }
            }
        }
    }

    private void ensureDecoder() throws Exception {
        if (this.decoder != null) {
            return;
        }
        this.decoder = H264Decoder.builder()
            .flushBehavior(H264Decoder.FlushBehavior.NoFlush)
            .build();
        this.lifecycleLog.accept("openh264 H.264 decoder initialised, awaiting decoded frames");
    }

    private static BufferedImage rgbaToImage(byte[] rgba, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[width * height];
        for (int i = 0, p = 0; i < argb.length; i++, p += 4) {
            int red = rgba[p] & 0xFF;
            int green = rgba[p + 1] & 0xFF;
            int blue = rgba[p + 2] & 0xFF;
            int alpha = rgba[p + 3] & 0xFF;
            argb[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    @Override
    public void close() {
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            if (this.decoder != null) {
                try {
                    this.decoder.close();
                } catch (Exception ignored) {
                    // Best-effort during stream cleanup.
                }
                this.decoder = null;
            }
        }
    }

    boolean failed() {
        return this.failed;
    }
}
