package dev.nexbit.bitcam.clientcommon;

public final class LocalPreviewStore {
    private volatile LocalPreviewFrame frame;

    public void accept(EncodedLocalFrame frame) {
        this.frame = new LocalPreviewFrame(
            frame.frameId(),
            frame.width(),
            frame.height(),
            frame.captureTimeMillis(),
            frame.payload(),
            System.currentTimeMillis()
        );
    }

    public void accept(LocalPreviewFrame frame) {
        this.frame = frame;
    }

    public LocalPreviewFrame frame() {
        return this.frame;
    }

    public void clear() {
        this.frame = null;
    }
}
