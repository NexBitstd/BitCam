package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.javah264.NativeCameraStream;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * A {@link CameraCaptureSession} backed by the native javah264 camera (nokhwa). Frames are grabbed
 * straight into a reused direct RGBA buffer and copied into a {@link CapturedCameraFrame}.
 */
final class NativeCameraSession implements CameraCaptureSession {
    private final NativeCameraStream stream;
    private final CameraCaptureMode captureMode;
    private ByteBuffer buffer;

    NativeCameraSession(NativeCameraStream stream, CameraCaptureMode captureMode) {
        this.stream = stream;
        this.captureMode = captureMode == null ? CameraCaptureMode.AUTO : captureMode;
    }

    @Override
    public boolean isOpen() {
        return this.stream.isOpen();
    }

    @Override
    public CameraCaptureMode captureMode() {
        return this.captureMode;
    }

    @Override
    public BufferedImage captureFrame() {
        CameraCaptureMode mode = this.captureMode.isSpecified() ? this.captureMode : CameraCaptureMode.DEFAULT;
        CapturedCameraFrame frame = this.captureFrame(mode.width(), mode.height());
        return frame == null ? null : frame.toBufferedImage();
    }

    @Override
    public CapturedCameraFrame captureFrame(int targetWidth, int targetHeight) {
        int width = normalizeDimension(targetWidth);
        int height = normalizeDimension(targetHeight);
        int needed = width * height * 4;
        if (this.buffer == null || this.buffer.capacity() < needed) {
            this.buffer = ByteBuffer.allocateDirect(needed);
        }

        this.buffer.clear();
        if (!this.stream.grabInto(this.buffer, width, height)) {
            return null;
        }

        // The native side wrote exactly width*height*4 RGBA bytes (it resizes to the requested size).
        byte[] rgba = new byte[needed];
        this.buffer.position(0);
        this.buffer.get(rgba);
        return new CapturedCameraFrame(width, height, rgba);
    }

    @Override
    public void close() {
        this.stream.close();
    }

    private static int normalizeDimension(int value) {
        int normalized = Math.max(16, value);
        return (normalized & 1) == 0 ? normalized : normalized - 1;
    }
}
