package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class CameraCaptureController implements AutoCloseable {
    // Upper bound on how long start()/close() will wait for the previous grabber to release. The
    // close runs on the capture thread behind at most one in-flight grabImage(), so this only has to
    // cover a single capture plus the native release.
    private static final long CLOSE_TIMEOUT_MILLIS = 3_000L;
    // The local preview is shown tiny on screen (own bubble / HUD / settings), so it never needs the
    // full stream resolution. Capping it keeps the capture thread's JPEG encode and the render thread's
    // JPEG decode cheap even when the network stream is 720p.
    private static final int PREVIEW_MAX_HEIGHT = 240;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-camera-capture");
        thread.setDaemon(true);
        return thread;
    });
    // Heavy H.264 encoding runs here, off the capture thread, so it never stalls capture or preview.
    // Queue depth 1 + DiscardOldestPolicy keeps only the newest frame: a stale video frame is useless.
    private final ThreadPoolExecutor networkEncodeExecutor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),
        runnable -> {
            Thread thread = new Thread(runnable, "bitcam-network-encode");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    // The local preview (own bubble, HUD, settings) is always JPEG: it's shown locally so bandwidth is
    // irrelevant, it needs no native libraries, and it avoids decoding our own inter-frame H.264 stream
    // just to look at ourselves.
    private final JpegFrameEncoder previewEncoder = new JpegFrameEncoder();
    private final AdaptiveBitrateController bitrateController = new AdaptiveBitrateController();
    // Serializes start()/stop()/close() so the session and capture task are never reconfigured by two
    // threads at once. The grabber itself is only ever touched on the capture thread (see stopLocked).
    private final Object lifecycleLock = new Object();
    // The network encoder is always H.264; null when this controller is running preview-only (e.g. the
    // settings screen), so we don't spin up an H.264 encoder — and its native deps — just to preview.
    private volatile FrameEncoder encoder;
    private volatile CameraCaptureSession session;
    private ScheduledFuture<?> captureTask;
    // The close of the previous session, scheduled on the capture thread; start() waits on it so a new
    // grabber never opens the device before the old one has released it (DirectShow reports "busy").
    private Future<?> pendingClose;
    // Preview frame ids may have gaps (preview is local-only); network frame ids must be gapless so
    // viewers don't mistake an encoder-buffered frame for a lost one.
    private int previewFrameCounter;
    private int networkFrameCounter;

    public void start(
        String preferredCameraName,
        CameraCaptureMode captureMode,
        int outputWidth,
        int outputHeight,
        int outputFps,
        float quality,
        Consumer<EncodedLocalFrame> networkConsumer,
        Consumer<EncodedLocalFrame> previewConsumer,
        Consumer<Throwable> failureConsumer
    ) {
        synchronized (this.lifecycleLock) {
            // Tear down any existing capture and wait for its grabber to fully release before opening
            // the device again — otherwise the OS still considers the camera in use.
            this.stopLocked();
            this.awaitPendingClose();

            CameraCaptureSession opened;
            try {
                opened = CameraCatalog.openSession(preferredCameraName, captureMode);
            } catch (Throwable exception) {
                throw exception instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Failed to open the selected camera", exception);
            }

            this.session = opened;
            this.encoder = networkConsumer == null ? null : new H264FrameEncoder();
            this.bitrateController.reset();

            int captureFps = opened.captureMode().isSpecified() ? opened.captureMode().fps() : outputFps;
            int effectiveFps = Math.max(1, Math.min(outputFps, captureFps));
            long periodMillis = Math.max(1L, 1000L / effectiveFps);
            this.captureTask = this.executor.scheduleAtFixedRate(
                () -> this.capture(outputWidth, outputHeight, effectiveFps, quality, networkConsumer, previewConsumer, failureConsumer),
                0L,
                periodMillis,
                TimeUnit.MILLISECONDS
            );
        }
    }

    private void capture(
        int width,
        int height,
        int fps,
        float quality,
        Consumer<EncodedLocalFrame> networkConsumer,
        Consumer<EncodedLocalFrame> previewConsumer,
        Consumer<Throwable> failureConsumer
    ) {
        // Snapshot the session so a concurrent stop() that nulls the field can't NPE us mid-capture; the
        // grabber stays valid because stop()'s close is queued behind this task on the same thread.
        CameraCaptureSession activeSession = this.session;
        if (activeSession == null || !activeSession.isOpen()) {
            return;
        }

        try {
            BufferedImage image = activeSession.captureFrame();
            if (image == null) {
                return;
            }

            long captureTime = System.currentTimeMillis();

            // Local preview is always JPEG and shown every frame regardless of the network stream. It is
            // downscaled to a small size so encoding it here (and decoding it on the render thread) stays
            // cheap even when the stream itself is 720p; the aspect ratio is preserved for the bubble.
            int previewHeight = Math.min(height, PREVIEW_MAX_HEIGHT);
            int previewWidth = Math.max(1, Math.round((float) width * previewHeight / Math.max(1, height)));
            int previewFrameId = ++this.previewFrameCounter;
            previewConsumer.accept(this.previewEncoder.encode(image, previewWidth, previewHeight, fps, quality, previewFrameId, captureTime));

            FrameEncoder networkEncoder = this.encoder;
            if (networkEncoder == null) {
                return; // preview-only session — nothing to transmit
            }

            // Hand the network encoder a private copy (the grabber reuses one buffer per frame) and let
            // it encode on its own thread, so a slow H.264 frame never delays the next capture/preview.
            BufferedImage networkSource = VideoFrameSupport.copy(image);
            this.networkEncodeExecutor.execute(() -> this.encodeAndSend(
                networkEncoder, networkSource, width, height, fps, quality, captureTime, networkConsumer, failureConsumer
            ));
        } catch (Throwable exception) {
            synchronized (this.lifecycleLock) {
                this.stopLocked();
            }
            if (failureConsumer != null) {
                failureConsumer.accept(exception);
            }
        }
    }

    // Runs on bitcam-network-encode. The encoder instance is only ever touched on this single thread
    // (encode here, close in stopLocked), so its inter-frame state stays consistent; setBitrateScale and
    // requestKeyframe remain volatile for cross-thread calls.
    private void encodeAndSend(
        FrameEncoder networkEncoder,
        BufferedImage source,
        int width,
        int height,
        int fps,
        float quality,
        long captureTime,
        Consumer<EncodedLocalFrame> networkConsumer,
        Consumer<Throwable> failureConsumer
    ) {
        // Streaming was stopped or restarted while this frame sat queued: the encoder is being torn down,
        // so drop the frame rather than touch a closing encoder.
        if (networkEncoder != this.encoder) {
            return;
        }
        try {
            // Apply the latest congestion-driven bitrate before encoding the network frame.
            networkEncoder.setBitrateScale(this.bitrateController.pollScale(System.nanoTime()));
            // Reserve the next network id but only commit it when the encoder actually emits a frame —
            // a buffered (null) frame must not consume an id, or viewers see a phantom gap and request
            // needless keyframes / inflate their reported loss.
            EncodedLocalFrame networkFrame = networkEncoder.encode(
                source, width, height, fps, quality, this.networkFrameCounter + 1, captureTime
            );
            if (networkFrame != null) {
                this.networkFrameCounter++;
                networkConsumer.accept(networkFrame);
            }
        } catch (Throwable exception) {
            synchronized (this.lifecycleLock) {
                this.stopLocked();
            }
            if (failureConsumer != null) {
                failureConsumer.accept(exception);
            }
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
        synchronized (this.lifecycleLock) {
            this.stopLocked();
        }
    }

    // Detaches the current session/encoder and schedules their close on the capture thread. Closing
    // there — behind any in-flight grabImage() on the same single-thread executor — is what keeps the
    // non-thread-safe grabber from being released while it is being read. Must hold lifecycleLock.
    private void stopLocked() {
        if (this.captureTask != null) {
            this.captureTask.cancel(false);
            this.captureTask = null;
        }

        CameraCaptureSession sessionToClose = this.session;
        FrameEncoder encoderToClose = this.encoder;
        this.session = null;
        this.encoder = null;

        // Close the grabber on the capture thread, behind any in-flight grabImage(); start() awaits this
        // so the device is released before reopening.
        if (sessionToClose != null) {
            this.pendingClose = this.executor.submit(() -> closeQuietly(sessionToClose));
        }
        // Close the encoder on the network-encode thread, behind any in-flight encode (DiscardOldestPolicy
        // drops a queued frame in favour of this close), since that is the only thread that touches it.
        if (encoderToClose != null) {
            this.networkEncodeExecutor.execute(() -> closeQuietly(encoderToClose));
        }
    }

    // Waits for the scheduled close of the previous session to finish so the device is released before
    // we reopen it. Must hold lifecycleLock. Best-effort: on timeout we proceed and let the backend's
    // open retry absorb a still-releasing device rather than block streaming indefinitely.
    private void awaitPendingClose() {
        Future<?> close = this.pendingClose;
        this.pendingClose = null;
        if (close == null) {
            return;
        }
        try {
            close.get(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException ignored) {
            // Proceed anyway; openSession() retries a busy device.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {
            // Sessions/encoders are best-effort to close during restart/shutdown.
        }
    }

    @Override
    public void close() {
        synchronized (this.lifecycleLock) {
            this.stopLocked();
            this.awaitPendingClose();
        }
        this.executor.shutdownNow();
        this.networkEncodeExecutor.shutdownNow();
    }
}
