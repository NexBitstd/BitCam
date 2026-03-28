package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

public final class JavaCvCameraBackend implements CameraBackend {
    private static final Duration FFMPEG_LIST_TIMEOUT = Duration.ofSeconds(3);
    private static final Pattern FFMPEG_DEVICE_PATTERN = Pattern.compile("^.*\\[(\\d+)]\\s+(.+)$");
    private static final Pattern FFMPEG_MODE_PATTERN = Pattern.compile("^.*?(\\d+)x(\\d+)@\\[(\\d+(?:\\.\\d+)?)\\s+(\\d+(?:\\.\\d+)?)\\]fps.*$");
    private static final CameraCaptureMode PROBE_MODE = new CameraCaptureMode(1, 1, 30);
    private volatile List<CameraDeviceInfo> cachedDevices = List.of();
    private volatile String cachedFailureMessage = "";
    private volatile boolean scanCompleted;
    private final Map<String, List<CameraCaptureMode>> cachedModes = new ConcurrentHashMap<>();

    @Override
    public String backendName() {
        return "JavaCV/FFmpeg AVFoundation";
    }

    @Override
    public List<CameraDeviceInfo> listDevices() throws FrameGrabber.Exception {
        if (this.scanCompleted) {
            if (!this.cachedDevices.isEmpty()) {
                return this.cachedDevices;
            }

            if (!this.cachedFailureMessage.isBlank()) {
                throw new IllegalStateException(this.cachedFailureMessage);
            }
        }

        FFmpegFrameGrabber.tryLoad();
        List<CameraDeviceInfo> devices = enumerateViaFfmpegCli();
        this.cachedDevices = List.copyOf(devices);
        this.scanCompleted = true;

        if (!devices.isEmpty()) {
            this.cachedFailureMessage = "";
            return this.cachedDevices;
        }

        this.cachedFailureMessage = "No AVFoundation camera devices were detected. If you just granted permission, restart the client and press Refresh.";
        throw new IllegalStateException(this.cachedFailureMessage);
    }

    @Override
    public List<CameraCaptureMode> listModes(String preferredCameraId) throws FrameGrabber.Exception {
        FFmpegFrameGrabber.tryLoad();
        String deviceId = resolveDeviceId(preferredCameraId);
        if (deviceId.isBlank()) {
            return List.of();
        }

        return this.cachedModes.computeIfAbsent(deviceId, this::probeModesViaFfmpegCli);
    }

    @Override
    public CameraCaptureSession openSession(String preferredCameraId, CameraCaptureMode requestedMode) throws FrameGrabber.Exception {
        FFmpegFrameGrabber.tryLoad();

        String deviceId = resolveDeviceId(preferredCameraId);
        if (deviceId.isBlank()) {
            throw new IllegalStateException("No AVFoundation camera devices are available");
        }

        List<CameraCaptureMode> supportedModes = this.listModes(deviceId);
        List<CameraCaptureMode> candidates = buildCandidateModes(requestedMode, supportedModes);
        List<String> failures = new ArrayList<>();

        for (CameraCaptureMode mode : candidates) {
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(deviceId + ":none");
            configureGrabber(grabber, mode);
            try {
                grabber.start();
                return new Session(grabber, mode);
            } catch (FrameGrabber.Exception exception) {
                failures.add(mode.label() + ": " + summarize(exception));
                try {
                    grabber.release();
                } catch (FrameGrabber.Exception ignored) {
                    // The start failure already contains the useful context.
                }
            }
        }

        throw new IllegalStateException(
            "Failed to open camera in any supported mode. Tried: " + String.join(" | ", failures)
        );
    }

    @Override
    public void invalidateDeviceCache() {
        this.cachedDevices = List.of();
        this.cachedModes.clear();
        this.cachedFailureMessage = "";
        this.scanCompleted = false;
    }

    private List<CameraDeviceInfo> enumerateViaFfmpegCli() {
        String ffmpegBinary = resolveFfmpegBinary();
        if (ffmpegBinary == null) {
            return List.of();
        }

        Process process = null;
        try {
            process = new ProcessBuilder(ffmpegBinary, "-f", "avfoundation", "-list_devices", "true", "-i", "")
                .redirectErrorStream(true)
                .start();

            List<CameraDeviceInfo> devices = new ArrayList<>();
            boolean inVideoSection = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("AVFoundation video devices:")) {
                        inVideoSection = true;
                        continue;
                    }

                    if (line.contains("AVFoundation audio devices:")) {
                        break;
                    }

                    if (!inVideoSection) {
                        continue;
                    }

                    Matcher matcher = FFMPEG_DEVICE_PATTERN.matcher(line.trim());
                    if (!matcher.matches()) {
                        continue;
                    }

                    String deviceId = matcher.group(1);
                    String deviceName = matcher.group(2).trim();
                    if (isScreenCaptureDevice(deviceName)) {
                        continue;
                    }
                    devices.add(new CameraDeviceInfo(deviceId, deviceName));
                }
            }

            process.waitFor(FFMPEG_LIST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return devices;
        } catch (Exception ignored) {
            return List.of();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private String resolveDeviceId(String preferredCameraId) throws FrameGrabber.Exception {
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

    private List<CameraCaptureMode> probeModesViaFfmpegCli(String deviceId) {
        String ffmpegBinary = resolveFfmpegBinary();
        if (ffmpegBinary == null) {
            return CameraCaptureMode.COMMON_FALLBACKS;
        }

        Process process = null;
        try {
            process = new ProcessBuilder(
                ffmpegBinary,
                "-f",
                "avfoundation",
                "-video_size",
                PROBE_MODE.width() + "x" + PROBE_MODE.height(),
                "-framerate",
                Integer.toString(PROBE_MODE.fps()),
                "-i",
                deviceId + ":none",
                "-t",
                "0.1",
                "-f",
                "null",
                "-"
            ).redirectErrorStream(true).start();

            LinkedHashSet<CameraCaptureMode> modes = new LinkedHashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = FFMPEG_MODE_PATTERN.matcher(line.trim());
                    if (!matcher.matches()) {
                        continue;
                    }

                    int width = Integer.parseInt(matcher.group(1));
                    int height = Integer.parseInt(matcher.group(2));
                    double minFps = Double.parseDouble(matcher.group(3));
                    double maxFps = Double.parseDouble(matcher.group(4));
                    addModeVariants(modes, width, height, minFps, maxFps);
                }
            }

            process.waitFor(FFMPEG_LIST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!modes.isEmpty()) {
                return List.copyOf(modes);
            }
        } catch (Exception ignored) {
            // Fall back to common modes below.
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return CameraCaptureMode.COMMON_FALLBACKS;
    }

    private static void configureGrabber(FFmpegFrameGrabber grabber, CameraCaptureMode mode) {
        grabber.setFormat("avfoundation");
        grabber.setOption("pixel_format", "bgr0");
        if (mode != null && mode.isSpecified()) {
            grabber.setImageWidth(mode.width());
            grabber.setImageHeight(mode.height());
            grabber.setFrameRate(mode.fps());
            grabber.setOption("framerate", Integer.toString(Math.max(1, mode.fps())));
            grabber.setOption("video_size", mode.width() + "x" + mode.height());
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

    private static void addModeVariants(LinkedHashSet<CameraCaptureMode> modes, int width, int height, double minFps, double maxFps) {
        int min = Math.max(1, (int) Math.ceil(minFps));
        int max = Math.max(min, (int) Math.round(maxFps));

        LinkedHashMap<Integer, CameraCaptureMode> byFps = new LinkedHashMap<>();
        byFps.put(max, new CameraCaptureMode(width, height, max));
        byFps.put(min, new CameraCaptureMode(width, height, min));

        for (CameraCaptureMode mode : byFps.values()) {
            modes.add(mode);
        }
    }

    private static String resolveFfmpegBinary() {
        String configured = System.getProperty("bitcam.ffmpeg.path", "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }

        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }

        String separator = System.getProperty("path.separator", ":");
        for (String part : path.split(Pattern.quote(separator))) {
            if (part == null || part.isBlank()) {
                continue;
            }

            java.nio.file.Path candidate = java.nio.file.Path.of(part, "ffmpeg");
            if (java.nio.file.Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }

        if (isMacOs() && java.nio.file.Files.isExecutable(java.nio.file.Path.of("/opt/homebrew/bin/ffmpeg"))) {
            return "/opt/homebrew/bin/ffmpeg";
        }

        if (isMacOs() && java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/local/bin/ffmpeg"))) {
            return "/usr/local/bin/ffmpeg";
        }

        return null;
    }

    private static boolean isMacOs() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac");
    }

    private static boolean isScreenCaptureDevice(String deviceName) {
        String normalized = deviceName.toLowerCase(Locale.ROOT);
        return normalized.contains("capture screen") || normalized.contains("screen input") || normalized.startsWith("capture ");
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

    private static final class Session implements CameraCaptureSession {
        private final FFmpegFrameGrabber grabber;
        private final Java2DFrameConverter converter = new Java2DFrameConverter();
        private final CameraCaptureMode captureMode;
        private boolean open = true;

        private Session(FFmpegFrameGrabber grabber, CameraCaptureMode captureMode) {
            this.grabber = grabber;
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
        public BufferedImage captureFrame() throws FrameGrabber.Exception {
            Frame frame = this.grabber.grabImage();
            if (frame == null) {
                return null;
            }

            return this.converter.getBufferedImage(frame);
        }

        @Override
        public void close() throws FrameGrabber.Exception {
            try {
                this.grabber.stop();
            } finally {
                this.open = false;
                this.converter.close();
                this.grabber.release();
            }
        }
    }
}
