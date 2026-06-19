package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamVideoCodec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * H.264 (libx264) encoder built on {@link FFmpegFrameRecorder}, writing a raw Annex-B byte stream so
 * each frame is a self-delimiting access unit we can fragment and send like a JPEG.
 *
 * <p>Tuned for real-time: {@code preset=ultrafast}, {@code tune=zerolatency}, no B-frames, and
 * {@code flush_packets=1} so every frame's bytes are emitted immediately instead of being buffered.
 *
 * <p><b>Experimental:</b> this path needs in-game validation on each OS — encoder buffering and
 * per-frame flushing behaviour can vary by FFmpeg build.
 */
final class H264FrameEncoder implements FrameEncoder {
    private static final long FORCE_KEYFRAME_INTERVAL_NANOS = 500_000_000L;
    private static final long BITRATE_RECONFIGURE_INTERVAL_NANOS = 700_000_000L;
    // Ignore sub-10% scale changes so small AIMD wobble doesn't churn the recorder.
    private static final float BITRATE_CHANGE_THRESHOLD = 0.1F;

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    private final Java2DFrameConverter converter = new Java2DFrameConverter();
    private FFmpegFrameRecorder recorder;
    private int width;
    private int height;
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
        BufferedImage scaled = VideoFrameSupport.scale(source, targetWidth, targetHeight);
        try {
            this.maybeForceKeyframe();
            this.maybeAdaptBitrate();
            // Reset before (re)creating the recorder so that a fresh recorder's header bytes
            // (SPS/PPS) are captured together with this frame's IDR, keeping keyframes self-contained.
            this.sink.reset();
            this.ensureRecorder(targetWidth, targetHeight, fps, quality);
            this.recorder.record(this.converter.convert(scaled));

            byte[] payload = this.sink.toByteArray();
            if (payload.length == 0) {
                // The encoder buffered this frame; nothing to transmit yet.
                return null;
            }
            return new EncodedLocalFrame(
                frameId, targetWidth, targetHeight, captureTimeMillis, containsIdr(payload), BitCamVideoCodec.H264, payload
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                "Failed to encode H.264 frame: " + exception.getMessage()
                    + " — the camera/encoder native libraries may be missing or failed to load.",
                exception
            );
        }
    }

    private void maybeForceKeyframe() {
        if (!this.keyframeRequested) {
            return;
        }
        long now = System.nanoTime();
        if (this.recorder != null && (now - this.lastForcedKeyframeNanos) < FORCE_KEYFRAME_INTERVAL_NANOS) {
            // Coalesce bursts of requests (many viewers joining at once) into one keyframe; keep the
            // request pending so it is honoured once the interval elapses.
            return;
        }
        // Recreating the recorder restarts the stream with SPS/PPS + an IDR — FFmpegFrameRecorder has
        // no per-frame "force keyframe", and re-init is cheap relative to how rarely this fires.
        this.keyframeRequested = false;
        this.lastForcedKeyframeNanos = now;
        this.closeRecorder();
    }

    private void maybeAdaptBitrate() {
        if (this.recorder == null) {
            // No recorder yet — ensureRecorder will pick up the current scale when it builds one.
            return;
        }
        if (Math.abs(this.bitrateScale - this.appliedBitrateScale) < BITRATE_CHANGE_THRESHOLD) {
            return;
        }
        long now = System.nanoTime();
        if ((now - this.lastBitrateChangeNanos) < BITRATE_RECONFIGURE_INTERVAL_NANOS) {
            // Rate-limit reconfigures so AIMD adjustments don't rebuild the encoder every frame.
            return;
        }
        this.lastBitrateChangeNanos = now;
        // Rebuilding applies the new bitrate and emits a fresh IDR — exactly what viewers need after a
        // downshift: a clean reference at the lower rate. FFmpegFrameRecorder can't retune live.
        this.closeRecorder();
    }

    private void ensureRecorder(int targetWidth, int targetHeight, int fps, float quality) throws Exception {
        if (this.recorder != null && this.width == targetWidth && this.height == targetHeight) {
            return;
        }
        this.closeRecorder();
        // Native libs are platform-specific and may have been downloaded at runtime — make sure this
        // thread's context classloader can locate them before touching any FFmpeg class.
        CameraLibraryManager.applyToThread();

        FFmpegFrameRecorder created = new FFmpegFrameRecorder(this.sink, targetWidth, targetHeight, 0);
        created.setFormat("h264");
        created.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        created.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        created.setFrameRate(Math.max(1, fps));
        created.setGopSize(Math.max(1, fps * 2));
        created.setVideoOption("preset", "ultrafast");
        created.setVideoOption("tune", "zerolatency");
        created.setVideoOption("bf", "0");
        created.setOption("flush_packets", "1");
        this.appliedBitrateScale = this.bitrateScale;
        created.setVideoBitrate(bitrateFor(targetWidth, targetHeight, fps, quality, this.appliedBitrateScale));
        created.start();

        this.recorder = created;
        this.width = targetWidth;
        this.height = targetHeight;
    }

    private static int bitrateFor(int width, int height, int fps, float quality, float bitrateScale) {
        float clampedQuality = Math.clamp(quality, 0.1F, 1.0F);
        float clampedScale = Math.clamp(bitrateScale, 0.1F, 1.0F);
        // ~0.04–0.12 bits per pixel per frame keeps a talking-head bubble crisp while staying far
        // below MJPEG; e.g. 640x480@24 at quality 0.9 ≈ 0.7 Mbps. The congestion scale trims this
        // further on a degrading link.
        double bitsPerPixel = 0.12D * clampedQuality * clampedScale;
        long bitrate = Math.round((double) width * height * Math.max(1, fps) * bitsPerPixel);
        return (int) Math.max(120_000L, Math.min(8_000_000L, bitrate));
    }

    private static boolean containsIdr(byte[] annexB) {
        // An IDR slice (nal_unit_type == 5) marks a keyframe in the Annex-B start-code stream.
        for (int i = 0; (i + 3) < annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                if ((annexB[i + 3] & 0x1F) == 5) {
                    return true;
                }
            }
        }
        return false;
    }

    private void closeRecorder() {
        if (this.recorder != null) {
            try {
                this.recorder.close();
            } catch (Exception ignored) {
                // Best-effort during reconfigure/shutdown.
            }
            this.recorder = null;
        }
    }

    @Override
    public void close() {
        this.closeRecorder();
        this.converter.close();
    }
}
