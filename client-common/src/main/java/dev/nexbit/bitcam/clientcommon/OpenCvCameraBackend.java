package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.openblas.global.openblas_nolapack;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;

public final class OpenCvCameraBackend implements CameraBackend {
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final boolean IS_LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    private static final int MAX_CAMERA_INDEX = 16;
    private static final int OPEN_ATTEMPTS = 3;
    private static final long OPEN_RETRY_DELAY_MILLIS = 250L;

    private volatile List<CameraDeviceInfo> cachedDevices = List.of();
    private volatile String cachedFailureMessage = "";
    private volatile boolean scanCompleted;

    @Override
    public String backendName() {
        return "OpenCV VideoCapture";
    }

    @Override
    public List<CameraDeviceInfo> listDevices() {
        if (this.scanCompleted) {
            if (!this.cachedDevices.isEmpty()) {
                return this.cachedDevices;
            }
            if (!this.cachedFailureMessage.isBlank()) {
                throw new IllegalStateException(this.cachedFailureMessage);
            }
        }

        ensureOpenCvReady();
        ArrayList<CameraDeviceInfo> devices = new ArrayList<>();
        for (int index = 0; index < MAX_CAMERA_INDEX; index++) {
            if (canOpen(index)) {
                devices.add(new CameraDeviceInfo(Integer.toString(index), "Camera " + index));
            }
        }

        this.cachedDevices = List.copyOf(devices);
        this.scanCompleted = true;
        if (!devices.isEmpty()) {
            this.cachedFailureMessage = "";
            return this.cachedDevices;
        }

        this.cachedFailureMessage = noDevicesMessage();
        throw new IllegalStateException(this.cachedFailureMessage);
    }

    @Override
    public List<CameraCaptureMode> listModes(String preferredCameraId) {
        return CameraCaptureMode.COMMON_FALLBACKS;
    }

    @Override
    public CameraCaptureSession openSession(String preferredCameraId, CameraCaptureMode requestedMode) {
        ensureOpenCvReady();
        String cameraId = resolveDeviceId(preferredCameraId);
        if (cameraId.isBlank()) {
            throw new IllegalStateException("No OpenCV camera devices are available");
        }

        int index = parseCameraIndex(cameraId);
        List<CameraCaptureMode> candidates = buildCandidateModes(requestedMode, CameraCaptureMode.COMMON_FALLBACKS);
        List<String> failures = new ArrayList<>();

        for (CameraCaptureMode mode : candidates) {
            RuntimeException lastError = null;
            for (int attempt = 1; attempt <= OPEN_ATTEMPTS; attempt++) {
                VideoCapture capture = openCapture(index, mode);
                if (capture != null) {
                    return new Session(capture, mode);
                }
                lastError = new IllegalStateException("OpenCV could not open camera index " + index);
                if (attempt < OPEN_ATTEMPTS) {
                    sleepQuietly(OPEN_RETRY_DELAY_MILLIS);
                }
            }
            failures.add(mode.label() + ": " + summarize(lastError));
        }

        throw new IllegalStateException(
            "Failed to open OpenCV camera in any supported mode. Tried: " + String.join(" | ", failures)
        );
    }

    @Override
    public void invalidateDeviceCache() {
        this.cachedDevices = List.of();
        this.cachedFailureMessage = "";
        this.scanCompleted = false;
    }

    private String resolveDeviceId(String preferredCameraId) {
        List<CameraDeviceInfo> devices = this.cachedDevices.isEmpty() ? listDevices() : this.cachedDevices;
        if (devices.isEmpty()) {
            return "";
        }

        if (preferredCameraId != null && !preferredCameraId.isBlank()) {
            for (CameraDeviceInfo device : devices) {
                if (preferredCameraId.equals(device.id()) || preferredCameraId.equals(device.name())) {
                    return device.id();
                }
            }
        }

        return devices.getFirst().id();
    }

    private static boolean canOpen(int index) {
        VideoCapture capture = openCapture(index, CameraCaptureMode.DEFAULT);
        if (capture == null) {
            return false;
        }
        capture.release();
        capture.close();
        return true;
    }

    private static VideoCapture openCapture(int index, CameraCaptureMode mode) {
        for (int apiPreference : apiPreferences()) {
            VideoCapture capture = new VideoCapture();
            boolean opened = apiPreference == opencv_videoio.CAP_ANY
                ? capture.open(index)
                : capture.open(index, apiPreference);
            if (!opened || !capture.isOpened()) {
                capture.release();
                capture.close();
                continue;
            }

            configureCapture(capture, mode);
            return capture;
        }
        return null;
    }

    private static List<Integer> apiPreferences() {
        ArrayList<Integer> preferences = new ArrayList<>();
        if (IS_WINDOWS) {
            preferences.add(opencv_videoio.CAP_DSHOW);
            preferences.add(opencv_videoio.CAP_MSMF);
        } else if (IS_MAC) {
            preferences.add(opencv_videoio.CAP_AVFOUNDATION);
        } else if (IS_LINUX) {
            preferences.add(opencv_videoio.CAP_V4L2);
        }
        preferences.add(opencv_videoio.CAP_ANY);
        return preferences;
    }

    private static void configureCapture(VideoCapture capture, CameraCaptureMode mode) {
        capture.set(opencv_videoio.CAP_PROP_BUFFERSIZE, 1.0D);
        if (mode != null && mode.isSpecified()) {
            capture.set(opencv_videoio.CAP_PROP_FRAME_WIDTH, mode.width());
            capture.set(opencv_videoio.CAP_PROP_FRAME_HEIGHT, mode.height());
            capture.set(opencv_videoio.CAP_PROP_FPS, mode.fps());
        }
    }

    private static void ensureOpenCvReady() {
        CameraLibraryManager.applyToThread();
        try {
            Loader.load(openblas_nolapack.class);
            Loader.load(opencv_core.class);
            Loader.load(opencv_imgproc.class);
            Loader.load(opencv_videoio.class);
        } catch (Throwable exception) {
            throw new IllegalStateException("Failed to load OpenCV camera natives", exception);
        }
    }

    private static int parseCameraIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static List<CameraCaptureMode> buildCandidateModes(CameraCaptureMode requestedMode, List<CameraCaptureMode> supportedModes) {
        LinkedHashSet<CameraCaptureMode> candidates = new LinkedHashSet<>();
        CameraCaptureMode preferred = resolveRequestedMode(requestedMode, supportedModes);
        if (preferred != null && preferred.isSpecified()) {
            candidates.add(preferred);
        }

        if (!supportedModes.isEmpty()) {
            candidates.add(pickDefaultMode(supportedModes));
            candidates.addAll(supportedModes);
        } else if (requestedMode != null && requestedMode.isSpecified()) {
            candidates.add(requestedMode);
        }

        candidates.addAll(CameraCaptureMode.COMMON_FALLBACKS);
        return candidates.stream().filter(CameraCaptureMode::isSpecified).toList();
    }

    private static CameraCaptureMode resolveRequestedMode(CameraCaptureMode requestedMode, List<CameraCaptureMode> supportedModes) {
        if (requestedMode == null || requestedMode.isAuto()) {
            return supportedModes.isEmpty() ? CameraCaptureMode.DEFAULT : pickDefaultMode(supportedModes);
        }

        if (supportedModes.isEmpty()) {
            return requestedMode;
        }

        for (CameraCaptureMode supportedMode : supportedModes) {
            if (supportedMode.equals(requestedMode)) {
                return supportedMode;
            }
        }

        return supportedModes.stream()
            .min(Comparator.comparingInt(mode -> modeDistance(mode, requestedMode)))
            .orElseGet(() -> pickDefaultMode(supportedModes));
    }

    private static CameraCaptureMode pickDefaultMode(List<CameraCaptureMode> supportedModes) {
        if (supportedModes.isEmpty()) {
            return CameraCaptureMode.DEFAULT;
        }

        return supportedModes.stream()
            .min(Comparator.comparingInt(mode -> modeDistance(mode, CameraCaptureMode.DEFAULT)))
            .orElse(supportedModes.getFirst());
    }

    private static int modeDistance(CameraCaptureMode left, CameraCaptureMode right) {
        int widthDistance = Math.abs(left.width() - right.width());
        int heightDistance = Math.abs(left.height() - right.height());
        int fpsDistance = Math.abs(left.fps() - right.fps()) * 24;
        return widthDistance + heightDistance + fpsDistance;
    }

    private static String noDevicesMessage() {
        return "No OpenCV camera devices were detected. Make sure your webcam is connected and permissions are granted.";
    }

    private static String summarize(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Session implements CameraCaptureSession {
        private final VideoCapture capture;
        private final CameraCaptureMode captureMode;
        private final Mat raw = new Mat();
        private final Mat resized = new Mat();
        private final Mat rgba = new Mat();
        private boolean open = true;

        private Session(VideoCapture capture, CameraCaptureMode captureMode) {
            this.capture = capture;
            this.captureMode = captureMode;
        }

        @Override
        public boolean isOpen() {
            return this.open;
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
            if (!this.open || !this.capture.read(this.raw) || this.raw.empty()) {
                return null;
            }

            int width = normalizeDimension(targetWidth);
            int height = normalizeDimension(targetHeight);
            Mat source = this.raw;
            if (this.raw.cols() != width || this.raw.rows() != height) {
                opencv_imgproc.resize(this.raw, this.resized, new Size(width, height));
                source = this.resized;
            }

            int channels = source.channels();
            if (channels == 4) {
                opencv_imgproc.cvtColor(source, this.rgba, opencv_imgproc.COLOR_BGRA2RGBA);
            } else if (channels == 3) {
                opencv_imgproc.cvtColor(source, this.rgba, opencv_imgproc.COLOR_BGR2RGBA);
            } else if (channels == 1) {
                opencv_imgproc.cvtColor(source, this.rgba, opencv_imgproc.COLOR_GRAY2RGBA);
            } else {
                throw new IllegalStateException("Unsupported OpenCV camera frame channel count: " + channels);
            }

            byte[] rgbaBytes = new byte[width * height * 4];
            BytePointer data = this.rgba.data();
            data.position(0).get(rgbaBytes);
            return new CapturedCameraFrame(width, height, rgbaBytes);
        }

        @Override
        public void close() {
            if (!this.open) {
                return;
            }
            this.open = false;
            this.capture.release();
            this.capture.close();
            this.raw.close();
            this.resized.close();
            this.rgba.close();
        }

        private static int normalizeDimension(int value) {
            int normalized = Math.max(16, value);
            return (normalized & 1) == 0 ? normalized : normalized - 1;
        }
    }
}
