package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CameraCaptureController implements AutoCloseable {
    private final JpegFrameEncoder encoder = new JpegFrameEncoder();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-camera-capture");
        thread.setDaemon(true);
        return thread;
    });

    private CameraCaptureSession session;
    private ScheduledFuture<?> captureTask;
    private byte[] lastFrameBytes = new byte[0];
    private int frameCounter;

    public void start(
        String preferredCameraName,
        CameraCaptureMode captureMode,
        int outputWidth,
        int outputHeight,
        int outputFps,
        float quality,
        Consumer<EncodedLocalFrame> frameConsumer
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

        int captureFps = this.session.captureMode().isSpecified() ? this.session.captureMode().fps() : outputFps;
        long periodMillis = Math.max(1L, 1000L / Math.max(1, Math.min(outputFps, captureFps)));
        this.captureTask = this.executor.scheduleAtFixedRate(
            () -> this.capture(outputWidth, outputHeight, quality, frameConsumer),
            0L,
            periodMillis,
            TimeUnit.MILLISECONDS
        );
    }

    private void capture(int width, int height, float quality, Consumer<EncodedLocalFrame> frameConsumer) {
        if (this.session == null || !this.session.isOpen()) {
            return;
        }

        try {
            BufferedImage image = this.session.captureFrame();
            if (image == null) {
                return;
            }

            byte[] encoded = this.encoder.encode(image, width, height, quality);
            if (Arrays.equals(encoded, this.lastFrameBytes)) {
                return;
            }

            this.lastFrameBytes = encoded;
            frameConsumer.accept(new EncodedLocalFrame(++this.frameCounter, width, height, System.currentTimeMillis(), this.frameCounter % 30 == 1, encoded));
        } catch (Throwable exception) {
            this.stop();
        }
    }

    public void stop() {
        if (this.captureTask != null) {
            this.captureTask.cancel(false);
            this.captureTask = null;
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
