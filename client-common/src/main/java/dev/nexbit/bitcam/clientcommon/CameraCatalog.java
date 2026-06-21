package dev.nexbit.bitcam.clientcommon;

import java.nio.file.Path;
import java.util.List;

public final class CameraCatalog {
    private static volatile boolean backendConfigured;
    private static volatile CameraBackend backend;
    private static volatile boolean backendAvailable = true;
    private static volatile String backendStatusMessage = "";
    private static volatile boolean initInProgress = false;

    private CameraCatalog() {
    }

    /**
     * Warms up the native camera backend off the game thread. The javah264 native is bundled in the
     * jar (no download), so this just loads it and enumerates once.
     */
    public static void prewarm(Path cacheDir) {
        if (backendConfigured || initInProgress) {
            return;
        }
        initInProgress = true;
        Thread thread = new Thread(() -> {
            try {
                ensureBackendConfigured();
            } finally {
                initInProgress = false;
            }
        }, "bitcam-camera-prewarm");
        thread.setDaemon(true);
        thread.start();
    }

    public static boolean isInitializing() {
        return initInProgress && !backendConfigured;
    }

    public static List<CameraDeviceInfo> listDevices() {
        if (isInitializing()) {
            return List.of();
        }
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
        if (isInitializing()) {
            return List.of();
        }
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
            throw new IllegalStateException(backendStatusMessage.isBlank() ? "No camera backend is available" : backendStatusMessage);
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
        if (isInitializing()) {
            return "";
        }
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

    // The native javah264 backend (nokhwa) is the only one on every platform. Listing devices both
    // validates that the native loaded and confirms at least one camera is present.
    private static CameraBackend selectBackend() {
        CameraBackend candidate = new NativeCameraBackend();
        try {
            candidate.listDevices();
            return candidate;
        } catch (Throwable throwable) {
            backendAvailable = false;
            backendStatusMessage = summarize(throwable);
            return null;
        }
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
