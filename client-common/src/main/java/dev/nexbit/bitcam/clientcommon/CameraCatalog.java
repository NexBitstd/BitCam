package dev.nexbit.bitcam.clientcommon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CameraCatalog {
    private static volatile boolean backendConfigured;
    private static volatile CameraBackend backend;
    private static volatile boolean backendAvailable = true;
    private static volatile String backendStatusMessage = "";

    private CameraCatalog() {
    }

    public static List<CameraDeviceInfo> listDevices() {
        ensureBackendConfigured();
        if (!backendAvailable || backend == null) {
            return List.of();
        }

        try {
            backendStatusMessage = "";
            return backend.listDevices();
        } catch (Throwable throwable) {
            backendStatusMessage = "Camera backend failed while enumerating devices: " + summarize(throwable);
            return List.of();
        }
    }

    public static List<CameraDeviceInfo> refreshDevices() {
        synchronized (CameraCatalog.class) {
            if (backend != null) {
                backend.invalidateDeviceCache();
            }

            if (backend == null || !backendAvailable) {
                backendConfigured = false;
                backend = null;
                backendAvailable = true;
                backendStatusMessage = "";
            }
        }

        return listDevices();
    }

    public static List<CameraCaptureMode> listModes(String preferredCameraId) {
        ensureBackendConfigured();
        if (!backendAvailable || backend == null) {
            return List.of();
        }

        try {
            return backend.listModes(preferredCameraId);
        } catch (Throwable throwable) {
            backendStatusMessage = "Camera backend failed while reading supported modes: " + summarize(throwable);
            return List.of();
        }
    }

    public static CameraCaptureSession openSession(String preferredName, CameraCaptureMode captureMode) {
        ensureBackendConfigured();
        if (!backendAvailable || backend == null) {
            throw new IllegalStateException(backendStatusMessage.isBlank() ? "No compatible camera backend is available" : backendStatusMessage);
        }

        try {
            return backend.openSession(preferredName, captureMode);
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                "Failed to open camera with " + backend.backendName() + ": " + summarize(throwable),
                throwable
            );
        }
    }

    public static String statusMessage() {
        ensureBackendConfigured();
        return backendStatusMessage;
    }

    private static void ensureBackendConfigured() {
        if (backendConfigured) {
            return;
        }

        synchronized (CameraCatalog.class) {
            if (backendConfigured) {
                return;
            }

            backendAvailable = true;
            backendStatusMessage = "";
            backend = selectBackend();

            backendConfigured = true;
        }
    }

    private static CameraBackend selectBackend() {
        List<String> failures = new ArrayList<>();

        for (CameraBackend candidate : candidateBackends()) {
            try {
                candidate.listDevices();
                return candidate;
            } catch (Throwable throwable) {
                failures.add(candidate.backendName() + ": " + summarize(throwable));
            }
        }

        backendAvailable = false;
        backendStatusMessage = "No compatible camera backend is available: " + String.join(" | ", failures);
        return null;
    }

    private static List<CameraBackend> candidateBackends() {
        if (isMacOs()) {
            return List.of(new JavaCvCameraBackend());
        }

        return List.of(new WebcamCaptureBackend(), new JavaCvCameraBackend());
    }

    private static boolean isMacOs() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac");
    }

    private static String summarize(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }

        return message;
    }
}
