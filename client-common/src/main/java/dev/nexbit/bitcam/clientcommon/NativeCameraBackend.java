package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.javah264.CameraDevice;
import dev.nexbit.javah264.CameraVideoFormat;
import dev.nexbit.javah264.NativeCamera;
import dev.nexbit.javah264.NativeCameraStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The sole camera backend: native enumeration and capture via javah264 (nokhwa — AVFoundation on
 * macOS, V4L2 on Linux, MediaFoundation on Windows). Device names and real capture modes come from
 * the same backend that opens the stream, so there is no name↔index mismatch.
 */
public final class NativeCameraBackend implements CameraBackend {
    @Override
    public String backendName() {
        return "native camera";
    }

    @Override
    public List<CameraDeviceInfo> listDevices() throws Exception {
        List<CameraDevice> devices = NativeCamera.listDevices();
        if (devices.isEmpty()) {
            throw new IllegalStateException(noDevicesMessage());
        }

        // Key cameras by NAME, not the nokhwa ordinal: on macOS the query order isn't stable across
        // launches (virtual and Continuity cameras reshuffle it), so an ordinal saved in the config
        // would later point at a different camera. Sorting by name also keeps the dropdown from jumping.
        List<CameraDeviceInfo> result = new ArrayList<>(devices.size());
        for (CameraDevice device : devices) {
            result.add(new CameraDeviceInfo(device.name(), device.name()));
        }
        result.sort(Comparator.comparing(CameraDeviceInfo::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Override
    public List<CameraCaptureMode> listModes(String preferredCameraId) throws Exception {
        int ordinal = resolveOrdinal(preferredCameraId);
        if (ordinal < 0) {
            return List.of();
        }

        List<CameraVideoFormat> formats = NativeCamera.listFormats(ordinal);
        LinkedHashSet<CameraCaptureMode> modes = new LinkedHashSet<>();
        for (CameraVideoFormat format : formats) {
            if (format.width() > 0 && format.height() > 0 && format.fps() > 0) {
                modes.add(new CameraCaptureMode(format.width(), format.height(), format.fps()));
            }
        }

        return modes.stream()
            .sorted(Comparator.comparingInt(CameraCaptureMode::area).thenComparingInt(CameraCaptureMode::fps))
            .toList();
    }

    @Override
    public CameraCaptureSession openSession(String preferredCameraId, CameraCaptureMode captureMode) throws Exception {
        int ordinal = resolveOrdinal(preferredCameraId);
        if (ordinal < 0) {
            throw new IllegalStateException("No native camera devices are available");
        }

        boolean specified = captureMode != null && captureMode.isSpecified();
        int width = specified ? captureMode.width() : 0;
        int height = specified ? captureMode.height() : 0;
        int fps = specified ? captureMode.fps() : 0;

        NativeCameraStream stream = new NativeCameraStream(ordinal, width, height, fps);
        return new NativeCameraSession(stream, captureMode);
    }

    private static int resolveOrdinal(String preferredCameraId) throws Exception {
        List<CameraDevice> devices = NativeCamera.listDevices();
        if (devices.isEmpty()) {
            return -1;
        }

        if (preferredCameraId != null && !preferredCameraId.isBlank()) {
            for (CameraDevice device : devices) {
                if (preferredCameraId.equals(device.name())) {
                    return device.index();
                }
            }
        }

        // No (valid) preference: pick the first camera by name so it matches the dropdown's order.
        return devices.stream()
            .min(Comparator.comparing(CameraDevice::name, String.CASE_INSENSITIVE_ORDER))
            .map(CameraDevice::index)
            .orElseGet(() -> devices.getFirst().index());
    }

    private static String noDevicesMessage() {
        return "No camera devices were detected. Make sure your webcam is connected and permissions are granted.";
    }
}
