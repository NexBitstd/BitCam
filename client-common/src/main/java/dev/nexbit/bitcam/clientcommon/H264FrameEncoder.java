package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamVideoCodec;
import dev.nexbit.javah264.H264Encoder;
import java.awt.image.BufferedImage;

/**
 * OpenH264 encoder backed by javah264, writing one Annex-B access unit per captured frame.
 *
 * <p>The UDP protocol sees a complete H.264 AU that
 * can be fragmented, FEC-protected and reassembled without codec-specific transport changes.
 */
final class H264FrameEncoder implements FrameEncoder {
    private static final long FORCE_KEYFRAME_INTERVAL_NANOS = 500_000_000L;
    private static final long BITRATE_RECONFIGURE_INTERVAL_NANOS = 700_000_000L;
    // Ignore sub-10% scale changes so small AIMD wobble does not churn the encoder.
    private static final float BITRATE_CHANGE_THRESHOLD = 0.1F;

    private final Object lock = new Object();
    private H264Encoder encoder;
    private int width;
    private int height;
    private int fps;
    private volatile boolean keyframeRequested;
    private long lastForcedKeyframeNanos;
    private volatile float bitrateScale = 1.0F;
    private float appliedBitrateScale = 1.0F;
    private long lastBitrateChangeNanos;

    @Override
    public BitCamVideoCodec codec() {
        return BitCamVideoCodec.H264;
    }

    @Override
    public void requestKeyframe() {
        this.keyframeRequested = true;
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
        int encodedWidth = normalizedDimension(targetWidth);
        int encodedHeight = normalizedDimension(targetHeight);
        return this.encode(CapturedCameraFrame.fromBufferedImage(source, encodedWidth, encodedHeight), fps, quality, frameId, captureTimeMillis);
    }

    @Override
    public EncodedLocalFrame encode(
        CapturedCameraFrame source,
        int fps,
        float quality,
        int frameId,
        long captureTimeMillis
    ) {
        int encodedWidth = normalizedDimension(source.width());
        int encodedHeight = normalizedDimension(source.height());
        CapturedCameraFrame frame = source;
        if (encodedWidth != source.width() || encodedHeight != source.height()) {
            frame = CapturedCameraFrame.fromBufferedImage(source.toBufferedImage(), encodedWidth, encodedHeight);
        }

        synchronized (this.lock) {
            try {
                this.maybeForceKeyframe();
                this.maybeAdaptBitrate();
                this.ensureEncoder(encodedWidth, encodedHeight, fps, quality);

                byte[] payload = this.encoder.encodeRGBA(encodedWidth, encodedHeight, frame.rgbaPixels());
                if (payload == null || payload.length == 0) {
                    return null;
                }
                return new EncodedLocalFrame(
                    frameId,
                    encodedWidth,
                    encodedHeight,
                    captureTimeMillis,
                    containsIdr(payload),
                    BitCamVideoCodec.H264,
                    payload
                );
            } catch (Exception exception) {
                throw new IllegalStateException(
                    "Failed to encode H.264 frame: " + exception.getMessage()
                        + " — openh264 native may be missing or failed to load.",
                    exception
                );
            }
        }
    }

    private void maybeForceKeyframe() {
        if (!this.keyframeRequested) {
            return;
        }
        long now = System.nanoTime();
        if (this.encoder != null && (now - this.lastForcedKeyframeNanos) < FORCE_KEYFRAME_INTERVAL_NANOS) {
            // Coalesce bursts of requests; keep the request pending until the interval elapses.
            return;
        }
        this.keyframeRequested = false;
        this.lastForcedKeyframeNanos = now;
        this.closeEncoder();
    }

    private void maybeAdaptBitrate() {
        if (this.encoder == null) {
            return;
        }
        if (Math.abs(this.bitrateScale - this.appliedBitrateScale) < BITRATE_CHANGE_THRESHOLD) {
            return;
        }
        long now = System.nanoTime();
        if ((now - this.lastBitrateChangeNanos) < BITRATE_RECONFIGURE_INTERVAL_NANOS) {
            return;
        }
        this.lastBitrateChangeNanos = now;
        this.closeEncoder();
    }

    private void ensureEncoder(int targetWidth, int targetHeight, int targetFps, float quality) throws Exception {
        int effectiveFps = Math.max(1, targetFps);
        if (this.encoder != null && this.width == targetWidth && this.height == targetHeight && this.fps == effectiveFps) {
            return;
        }

        this.closeEncoder();
        this.appliedBitrateScale = this.bitrateScale;
        this.encoder = H264Encoder.builder()
            .profile(H264Encoder.Profile.Baseline)
            .rateControlMode(H264Encoder.RateControlMode.Bitrate)
            .spsPpsStrategy(H264Encoder.SpsPpsStrategy.IncreasingId)
            .usageType(H264Encoder.UsageType.CameraVideoRealTime)
            .multipleThreadIdc((short) 1)
            .maxFrameRate((float) effectiveFps)
            .targetBitrate(bitrateFor(targetWidth, targetHeight, effectiveFps, quality, this.appliedBitrateScale))
            .intraFramePeriod(effectiveFps * 2)
            .build();
        this.width = targetWidth;
        this.height = targetHeight;
        this.fps = effectiveFps;
    }

    private static int bitrateFor(int width, int height, int fps, float quality, float bitrateScale) {
        float clampedQuality = Math.clamp(quality, 0.1F, 1.0F);
        float clampedScale = Math.clamp(bitrateScale, 0.1F, 1.0F);
        // ~0.04-0.12 bits per pixel per frame keeps a talking-head bubble crisp while staying far
        // below MJPEG; e.g. 640x480@24 at quality 0.9 is about 0.7 Mbps.
        double bitsPerPixel = 0.12D * clampedQuality * clampedScale;
        long bitrate = Math.round((double) width * height * Math.max(1, fps) * bitsPerPixel);
        return (int) Math.max(120_000L, Math.min(8_000_000L, bitrate));
    }

    private static int normalizedDimension(int value) {
        int normalized = Math.max(16, value);
        return (normalized & 1) == 0 ? normalized : normalized - 1;
    }

    private static boolean containsIdr(byte[] annexB) {
        for (int i = 0; i + 3 < annexB.length; i++) {
            int nalIndex = -1;
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                nalIndex = i + 3;
            } else if (i + 4 < annexB.length && annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 0 && annexB[i + 3] == 1) {
                nalIndex = i + 4;
            }
            if (nalIndex >= 0 && nalIndex < annexB.length && (annexB[nalIndex] & 0x1F) == 5) {
                return true;
            }
        }
        return false;
    }

    private void closeEncoder() {
        if (this.encoder != null) {
            try {
                this.encoder.close();
            } catch (Exception ignored) {
                // Best-effort during reconfigure/shutdown.
            }
            this.encoder = null;
        }
    }

    @Override
    public void close() {
        synchronized (this.lock) {
            this.closeEncoder();
        }
    }
}
