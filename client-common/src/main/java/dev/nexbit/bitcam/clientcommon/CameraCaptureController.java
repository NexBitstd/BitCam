package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CameraCaptureController implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-camera-capture");
        thread.setDaemon(true);
        return thread;
    });

    // The local preview (own bubble, HUD, settings) is always JPEG: it's shown locally so bandwidth is
    // irrelevant, it needs no native libraries, and it avoids decoding our own inter-frame H.264 stream
    // just to look at ourselves.
    private final JpegFrameEncoder previewEncoder = new JpegFrameEncoder();
    private final AdaptiveBitrateController bitrateController = new AdaptiveBitrateController();
    // The network encoder is always H.264; null when this controller is running preview-only (e.g. the
    // settings screen), so we don't spin up an H.264 encoder — and its native deps — just to preview.
    private volatile FrameEncoder encoder;
    private CameraCaptureSession session;
    private ScheduledFuture<?> captureTask;
    private int frameCounter;

    public void start(
        String preferredCameraName,
        CameraCaptureMode captureMode,
        int outputWidth,
        int outputHeight,
        int outputFps,
        float quality,
        Consumer<EncodedLocalFrame> networkConsumer,
        Consumer<EncodedLocalFrame> previewConsumer
    ) {
        this.stop();

        try {
            this.session = CameraCatalog.openSession(preferredCameraName, captureMode);
        } catch (Throwable exception) {
            this.stop();
            throw exception instanceof RuntimeException runtimeException
                ? runtimeException
                : new IllegalStateException("Failed to open the selected camera", exception);
        }

        this.encoder = networkConsumer == null ? null : new H264FrameEncoder();
        this.bitrateController.reset();

        int captureFps = this.session.captureMode().isSpecified() ? this.session.captureMode().fps() : outputFps;
        int effectiveFps = Math.max(1, Math.min(outputFps, captureFps));
        long periodMillis = Math.max(1L, 1000L / effectiveFps);
        this.captureTask = this.executor.scheduleAtFixedRate(
            () -> this.capture(outputWidth, outputHeight, effectiveFps, quality, networkConsumer, previewConsumer),
            0L,
            periodMillis,
            TimeUnit.MILLISECONDS
        );
    }

    private void capture(
        int width,
        int height,
        int fps,
        float quality,
        Consumer<EncodedLocalFrame> networkConsumer,
        Consumer<EncodedLocalFrame> previewConsumer
    ) {
        if (this.session == null || !this.session.isOpen()) {
            return;
        }

        try {
            BufferedImage image = this.session.captureFrame();
            if (image == null) {
                return;
            }

            int frameId = ++this.frameCounter;
            long captureTime = System.currentTimeMillis();

            // Local preview is always JPEG and shown every frame regardless of the network stream.
            previewConsumer.accept(this.previewEncoder.encode(image, width, height, fps, quality, frameId, captureTime));

            FrameEncoder networkEncoder = this.encoder;
            if (networkEncoder == null) {
                return; // preview-only session — nothing to transmit
            }

            // Apply the latest congestion-driven bitrate before encoding the network frame.
            networkEncoder.setBitrateScale(this.bitrateController.pollScale(System.nanoTime()));
            EncodedLocalFrame networkFrame = networkEncoder.encode(image, width, height, fps, quality, frameId, captureTime);
            if (networkFrame != null) {
                networkConsumer.accept(networkFrame);
            }
        } catch (Throwable exception) {
            this.stop();
        }
    }

    /** Asks the active encoder to emit a keyframe — invoked when a viewer requests one. */
    public void requestKeyframe() {
        FrameEncoder current = this.encoder;
        if (current != null) {
            current.requestKeyframe();
        }
    }

    /** Feeds a viewer's loss report (per-mille) into the congestion controller. */
    public void reportNetworkLoss(int lossPermille) {
        this.bitrateController.onReport(lossPermille);
    }

    public void stop() {
        if (this.captureTask != null) {
            this.captureTask.cancel(false);
            this.captureTask = null;
        }

        if (this.encoder != null) {
            try {
                this.encoder.close();
            } catch (Exception ignored) {
                // Encoders are best-effort to close during restart/shutdown.
            } finally {
                this.encoder = null;
            }
        }

        if (this.session != null) {
            try {
                this.session.close();
            } catch (Exception ignored) {
                // Capture sessions are best-effort to close during shutdown/restart.
            } finally {
                this.session = null;
            }
        }
    }

    @Override
    public void close() {
        this.stop();
        this.executor.shutdownNow();
    }
}
