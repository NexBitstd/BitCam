package dev.nexbit.bitcam.clientcommon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CameraCatalog {
    private static volatile boolean backendConfigured;
    private static volatile CameraBackend backend;
    private static volatile boolean backendAvailable = true;
    private static volatile String backendStatusMessage = "";
    private static volatile boolean initInProgress = false;

    private CameraCatalog() {
    }

    public static void prewarm(Path cacheDir) {
        if (backendConfigured || initInProgress) {
            return;
        }
        initInProgress = true;
        Thread thread = new Thread(() -> {
            try {
                // Ensure platform natives are available — downloads if not bundled.
                CameraLibraryManager.ensureReady(cacheDir);
                // Apply the native classloader so JavaCPP can find extracted libs.
                CameraLibraryManager.applyToThread();
                ensureBackendConfigured();
            } finally {
                initInProgress = false;
            }
        }, "bitcam-camera-prewarm");
        thread.setDaemon(true);
        thread.start();
    }

    public static boolean isInitializing() {
        return (initInProgress && !backendConfigured) || CameraLibraryManager.isDownloading();
    }

    public static List<CameraDeviceInfo> listDevices() {
        if (isInitializing()) {
            return List.of();
        }
        CameraLibraryManager.applyToThread();
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
        CameraLibraryManager.applyToThread();
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
        CameraLibraryManager.applyToThread();
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
        if (isInitializing()) {
            return "";
        }
        CameraLibraryManager.applyToThread();
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

        for (CameraBackend candidate : candidateBackends(failures)) {
            try {
                List<CameraDeviceInfo> devices = candidate.listDevices();
                if (devices.isEmpty()) {
                    failures.add(candidate.backendName() + ": no camera devices were reported");
                    continue;
                }
                return candidate;
            } catch (Throwable throwable) {
                failures.add(candidate.backendName() + ": " + summarize(throwable));
            }
        }

        backendAvailable = false;
        backendStatusMessage = "No compatible camera backend is available: " + String.join(" | ", failures);
        return null;
    }

    private static List<CameraBackend> candidateBackends(List<String> failures) {
        List<CameraBackend> candidates = new ArrayList<>(2);
        if (isMacOs()) {
            addBackendCandidate(candidates, failures, "JavaCV/FFmpeg AVFoundation", JavaCvCameraBackend::new);
            return List.copyOf(candidates);
        }

        // On Windows/Linux: try JavaCV (DirectShow/V4L2) first, then webcam-capture as fallback.
        addBackendCandidate(candidates, failures, "JavaCV/FFmpeg", JavaCvCameraBackend::new);
        addBackendCandidate(candidates, failures, "webcam-capture", WebcamCaptureBackend::new);
        return List.copyOf(candidates);
    }

    private static void addBackendCandidate(
        List<CameraBackend> candidates,
        List<String> failures,
        String backendName,
        BackendFactory factory
    ) {
        try {
            candidates.add(factory.create());
        } catch (Throwable throwable) {
            failures.add(backendName + ": " + summarize(throwable));
        }
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

    @FunctionalInterface
    private interface BackendFactory {
        CameraBackend create();
    }
}
