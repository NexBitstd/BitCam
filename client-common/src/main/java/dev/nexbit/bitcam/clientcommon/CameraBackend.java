package dev.nexbit.bitcam.clientcommon;

import java.util.List;

public interface CameraBackend {
    String backendName();

    List<CameraDeviceInfo> listDevices() throws Exception;

    default List<CameraCaptureMode> listModes(String preferredCameraId) throws Exception {
        return List.of();
    }

    CameraCaptureSession openSession(String preferredCameraName, CameraCaptureMode captureMode) throws Exception;

    default void invalidateDeviceCache() {
    }
}
