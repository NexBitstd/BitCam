package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;

public interface CameraCaptureSession extends AutoCloseable {
    boolean isOpen();

    default CameraCaptureMode captureMode() {
        return CameraCaptureMode.AUTO;
    }

    BufferedImage captureFrame() throws Exception;

    @Override
    void close() throws Exception;
}
