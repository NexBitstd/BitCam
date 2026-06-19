package dev.nexbit.bitcam.clientcommon;

public final class BitCamPreviewSession implements AutoCloseable {
    private static final int PREVIEW_WIDTH = 320;
    private static final int PREVIEW_HEIGHT = 180;
    private static final int PREVIEW_FPS = 12;
    private static final float PREVIEW_QUALITY = 0.88F;

    private final CameraCaptureController captureController = new CameraCaptureController();
    private final LocalPreviewStore previewStore = new LocalPreviewStore();

    public boolean start(String preferredCameraName, CameraCaptureMode captureMode) {
        try {
            this.captureController.start(
                preferredCameraName,
                captureMode,
                PREVIEW_WIDTH,
                PREVIEW_HEIGHT,
                PREVIEW_FPS,
                PREVIEW_QUALITY,
                null,
                this.previewStore::accept
            );
            return true;
        } catch (RuntimeException exception) {
            this.previewStore.clear();
            return false;
        }
    }

    public void restart(String preferredCameraName, CameraCaptureMode captureMode) {
        this.stop();
        this.start(preferredCameraName, captureMode);
    }

    public LocalPreviewStore previewStore() {
        return this.previewStore;
    }

    public void stop() {
        this.captureController.stop();
        this.previewStore.clear();
    }

    @Override
    public void close() {
        this.captureController.close();
        this.previewStore.clear();
    }
}
