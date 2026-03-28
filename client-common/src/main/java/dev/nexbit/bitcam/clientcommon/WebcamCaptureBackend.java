package dev.nexbit.bitcam.clientcommon;

import com.github.sarxos.webcam.Webcam;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public final class WebcamCaptureBackend implements CameraBackend {
    @Override
    public String backendName() {
        return "webcam-capture";
    }

    @Override
    public List<CameraDeviceInfo> listDevices() {
        return Webcam.getWebcams().stream()
            .map(webcam -> new CameraDeviceInfo(webcam.getName(), webcam.getName()))
            .toList();
    }

    @Override
    public List<CameraCaptureMode> listModes(String preferredCameraId) {
        Webcam webcam = resolvePreferred(preferredCameraId);
        if (webcam == null) {
            return List.of();
        }

        Dimension[] dimensions = webcam.getViewSizes();
        if (dimensions == null || dimensions.length == 0) {
            return CameraCaptureMode.COMMON_FALLBACKS;
        }

        ArrayList<CameraCaptureMode> modes = new ArrayList<>(dimensions.length);
        for (Dimension dimension : dimensions) {
            modes.add(new CameraCaptureMode(dimension.width, dimension.height, 30));
        }
        return modes;
    }

    @Override
    public CameraCaptureSession openSession(String preferredCameraName, CameraCaptureMode captureMode) {
        Webcam webcam = resolvePreferred(preferredCameraName);
        if (webcam == null) {
            throw new IllegalStateException("No webcam devices available");
        }

        CameraCaptureMode selectedMode = captureMode == null || !captureMode.isSpecified()
            ? listModes(preferredCameraName).stream().findFirst().orElse(CameraCaptureMode.DEFAULT)
            : captureMode;

        webcam.setCustomViewSizes(new Dimension(selectedMode.width(), selectedMode.height()));
        webcam.setViewSize(new Dimension(selectedMode.width(), selectedMode.height()));
        webcam.open();
        return new Session(webcam, selectedMode);
    }

    private Webcam resolvePreferred(String preferredCameraName) {
        List<Webcam> webcams = Webcam.getWebcams();
        if (preferredCameraName != null && !preferredCameraName.isBlank()) {
            for (Webcam webcam : webcams) {
                if (webcam.getName().equals(preferredCameraName)) {
                    return webcam;
                }
            }
        }

        return webcams.isEmpty() ? null : webcams.getFirst();
    }

    private static final class Session implements CameraCaptureSession {
        private final Webcam webcam;
        private final CameraCaptureMode captureMode;

        private Session(Webcam webcam, CameraCaptureMode captureMode) {
            this.webcam = webcam;
            this.captureMode = captureMode;
        }

        @Override
        public boolean isOpen() {
            return this.webcam.isOpen();
        }

        @Override
        public CameraCaptureMode captureMode() {
            return this.captureMode;
        }

        @Override
        public BufferedImage captureFrame() {
            return this.webcam.getImage();
        }

        @Override
        public void close() {
            this.webcam.close();
        }
    }
}
