package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;

public interface CameraCaptureSession extends AutoCloseable {
    boolean isOpen();

    default CameraCaptureMode captureMode() {
        return CameraCaptureMode.AUTO;
    }

    BufferedImage captureFrame() throws Exception;

    default CapturedCameraFrame captureFrame(int targetWidth, int targetHeight) throws Exception {
        BufferedImage image = this.captureFrame();
        return image == null ? null : CapturedCameraFrame.fromBufferedImage(image, targetWidth, targetHeight);
    }

    @Override
    void close() throws Exception;
}
