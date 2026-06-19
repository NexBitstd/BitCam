package dev.nexbit.bitcam.clientcommon;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

public final class JavaCvCameraBackend implements CameraBackend {
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private static final Duration FFMPEG_LIST_TIMEOUT = Duration.ofSeconds(4);
    private static final Pattern AVFOUNDATION_DEVICE_PATTERN = Pattern.compile("^.*\\[(\\d+)]\\s+(.+)$");
    private static final Pattern AVFOUNDATION_MODE_PATTERN = Pattern.compile("^.*?(\\d+)x(\\d+)@\\[(\\d+(?:\\.\\d+)?)\\s+(\\d+(?:\\.\\d+)?)\\]fps.*$");
    private static final Pattern DSHOW_MODE_PATTERN = Pattern.compile("min s=(\\d+)x(\\d+) fps=(\\d+(?:\\.\\d+)?) max s=\\d+x\\d+ fps=(\\d+(?:\\.\\d+)?)");
    private static final CameraCaptureMode PROBE_MODE = new CameraCaptureMode(1, 1, 30);

    private volatile List<CameraDeviceInfo> cachedDevices = List.of();
    private volatile String cachedFailureMessage = "";
    private volatile boolean scanCompleted;
    private final Map<String, List<CameraCaptureMode>> cachedModes = new ConcurrentHashMap<>();

    @Override
    public String backendName() {
        if (IS_WINDOWS) return "JavaCV/FFmpeg DirectShow";
        if (IS_MAC) return "JavaCV/FFmpeg AVFoundation";
        return "JavaCV/FFmpeg V4L2";
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

        ensureFfmpegReady();
        List<CameraDeviceInfo> devices = enumerateViaFfmpegCli();
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
    public List<CameraCaptureMode> listModes(String preferredCameraId) throws FrameGrabber.Exception {
        ensureFfmpegReady();
        String deviceId = resolveDeviceId(preferredCameraId);
        if (deviceId.isBlank()) {
            return List.of();
        }

        return this.cachedModes.computeIfAbsent(deviceId, this::probeModesViaFfmpegCli);
    }

    @Override
    public CameraCaptureSession openSession(String preferredCameraId, CameraCaptureMode requestedMode) throws FrameGrabber.Exception {
        ensureFfmpegReady();

        String deviceId = resolveDeviceId(preferredCameraId);
        if (deviceId.isBlank()) {
            throw new IllegalStateException("No " + backendName() + " camera devices are available");
        }

        List<CameraCaptureMode> supportedModes = this.listModes(deviceId);
        List<CameraCaptureMode> candidates = buildCandidateModes(requestedMode, supportedModes);
        List<String> failures = new ArrayList<>();

        for (CameraCaptureMode mode : candidates) {
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(grabberInput(deviceId));
            configureGrabber(grabber, mode);
            try {
                grabber.start();
                return new Session(grabber, mode);
            } catch (FrameGrabber.Exception exception) {
                failures.add(mode.label() + ": " + summarize(exception));
                try {
                    grabber.release();
                } catch (FrameGrabber.Exception ignored) {
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

    private static void ensureFfmpegReady() throws FrameGrabber.Exception {
        CameraLibraryManager.applyToThread();
        FFmpegFrameGrabber.tryLoad();
        CameraLibraryManager.configureFfmpegLoggingAfterLoad();
    }

    // --- Device enumeration ---

    private List<CameraDeviceInfo> enumerateViaFfmpegCli() {
        if (IS_WINDOWS) return enumerateDShow();
        if (IS_MAC) return enumerateAvFoundation();
        return enumerateV4l2();
    }

    private List<CameraDeviceInfo> enumerateAvFoundation() {
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
                    Matcher matcher = AVFOUNDATION_DEVICE_PATTERN.matcher(line.trim());
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

    private List<CameraDeviceInfo> enumerateDShow() {
        String ffmpegBinary = resolveFfmpegBinary();
        if (ffmpegBinary == null) {
            return List.of();
        }

        Process process = null;
        try {
            process = new ProcessBuilder(ffmpegBinary, "-f", "dshow", "-list_devices", "true", "-i", "dummy")
                .redirectErrorStream(true)
                .start();

            List<CameraDeviceInfo> devices = new ArrayList<>();
            boolean inVideoSection = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.contains("directshow video devices")) {
                        inVideoSection = true;
                        continue;
                    }
                    if (lower.contains("directshow audio devices")) {
                        break;
                    }
                    if (!inVideoSection || lower.contains("alternative name")) {
                        continue;
                    }
                    // Match: [dshow @ 0x...]  "Camera Name"  or  "Camera Name" (video)
                    int firstQuote = line.indexOf('"');
                    int lastQuote = line.lastIndexOf('"');
                    if (firstQuote < 0 || lastQuote <= firstQuote) {
                        continue;
                    }
                    // Skip if the name starts with @  (alternative name values)
                    String name = line.substring(firstQuote + 1, lastQuote);
                    if (name.startsWith("@") || name.isBlank()) {
                        continue;
                    }
                    devices.add(new CameraDeviceInfo(name, name));
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

    private List<CameraDeviceInfo> enumerateV4l2() {
        List<CameraDeviceInfo> devices = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Path videoDevice = Path.of("/dev/video" + i);
            if (!Files.exists(videoDevice)) {
                continue;
            }
            String name = readV4l2DeviceName(i);
            devices.add(new CameraDeviceInfo(videoDevice.toString(), name));
        }
        return devices;
    }

    private static String readV4l2DeviceName(int index) {
        Path namePath = Path.of("/sys/class/video4linux/video" + index + "/name");
        try {
            String name = Files.readString(namePath).trim();
            if (!name.isBlank()) {
                return name;
            }
        } catch (Exception ignored) {
        }
        return "/dev/video" + index;
    }

    // --- Mode probing ---

    private List<CameraCaptureMode> probeModesViaFfmpegCli(String deviceId) {
        if (IS_WINDOWS) return probeModesViaFfmpegCliDShow(deviceId);
        if (IS_MAC) return probeModesViaFfmpegCliAvFoundation(deviceId);
        return probeModesViaFfmpegCliV4l2(deviceId);
    }

    private List<CameraCaptureMode> probeModesViaFfmpegCliAvFoundation(String deviceId) {
        String ffmpegBinary = resolveFfmpegBinary();
        if (ffmpegBinary == null) {
            return CameraCaptureMode.COMMON_FALLBACKS;
        }

        Process process = null;
        try {
            process = new ProcessBuilder(
                ffmpegBinary,
                "-f", "avfoundation",
                "-video_size", PROBE_MODE.width() + "x" + PROBE_MODE.height(),
                "-framerate", Integer.toString(PROBE_MODE.fps()),
                "-i", deviceId + ":none",
                "-t", "0.1",
                "-f", "null", "-"
            ).redirectErrorStream(true).start();

            LinkedHashSet<CameraCaptureMode> modes = new LinkedHashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = AVFOUNDATION_MODE_PATTERN.matcher(line.trim());
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
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return CameraCaptureMode.COMMON_FALLBACKS;
    }

    private List<CameraCaptureMode> probeModesViaFfmpegCliDShow(String deviceId) {
        String ffmpegBinary = resolveFfmpegBinary();
        if (ffmpegBinary == null) {
            return CameraCaptureMode.COMMON_FALLBACKS;
        }

        Process process = null;
        try {
            process = new ProcessBuilder(
                ffmpegBinary,
                "-f", "dshow",
                "-list_options", "true",
                "-i", "video=" + deviceId
            ).redirectErrorStream(true).start();

            LinkedHashSet<CameraCaptureMode> modes = new LinkedHashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = DSHOW_MODE_PATTERN.matcher(line);
                    if (!matcher.find()) {
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
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return CameraCaptureMode.COMMON_FALLBACKS;
    }

    private List<CameraCaptureMode> probeModesViaFfmpegCliV4l2(String deviceId) {
        String ffmpegBinary = resolveFfmpegBinary();
        if (ffmpegBinary == null) {
            return CameraCaptureMode.COMMON_FALLBACKS;
        }

        // v4l2 -list_formats all outputs lines like:
        // [video4linux2,v4l2 @ ...] Raw       : yuyv422 : YUYV 4:2:2 : {width}x{height}
        // Not easy to parse FPS from this, so fall back to common fallbacks for now.
        // The grabber will negotiate a working mode from candidates.
        return CameraCaptureMode.COMMON_FALLBACKS;
    }

    // --- Grabber input per platform ---

    private static String grabberInput(String deviceId) {
        if (IS_WINDOWS) return "video=" + deviceId;
        if (IS_MAC) return deviceId + ":none";
        return deviceId; // v4l2: /dev/video0
    }

    // --- Grabber configuration ---

    private static void configureGrabber(FFmpegFrameGrabber grabber, CameraCaptureMode mode) {
        if (IS_WINDOWS) {
            grabber.setFormat("dshow");
        } else if (IS_MAC) {
            grabber.setFormat("avfoundation");
            grabber.setOption("pixel_format", "bgr0");
        } else {
            grabber.setFormat("v4l2");
            grabber.setOption("pixel_format", "bgr0");
        }

        if (mode != null && mode.isSpecified()) {
            grabber.setImageWidth(mode.width());
            grabber.setImageHeight(mode.height());
            grabber.setFrameRate(mode.fps());
            grabber.setOption("framerate", Integer.toString(Math.max(1, mode.fps())));
            grabber.setOption("video_size", mode.width() + "x" + mode.height());
        }
    }

    // --- Device ID resolution ---

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

    // --- Mode selection helpers ---

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

    // --- FFmpeg binary resolution ---

    private static String resolveFfmpegBinary() {
        // 1. User-configured override
        String configured = System.getProperty("bitcam.ffmpeg.path", "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }

        // 2. Use the FFmpeg executable extracted from downloaded platform natives.
        Path downloaded = CameraLibraryManager.ffmpegExecutablePath();
        if (downloaded != null && Files.isExecutable(downloaded)) {
            return downloaded.toAbsolutePath().toString();
        }

        // 3. Use the FFmpeg executable bundled with JavaCV — works without any system install.
        //    Loader.load() extracts the platform binary to a temp cache and returns its path.
        try {
            String bundled = Loader.load(ffmpeg.class);
            if (bundled != null && !bundled.isBlank()) {
                return bundled;
            }
        } catch (Exception ignored) {
        }

        // 4. Fall back to a system-installed ffmpeg in PATH
        String executableName = IS_WINDOWS ? "ffmpeg.exe" : "ffmpeg";
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isBlank()) {
            String separator = System.getProperty("path.separator", IS_WINDOWS ? ";" : ":");
            for (String part : pathEnv.split(Pattern.quote(separator))) {
                if (part == null || part.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(part, executableName);
                if (Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
        }

        if (IS_MAC) {
            for (String macPath : List.of("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg")) {
                if (Files.isExecutable(Path.of(macPath))) {
                    return macPath;
                }
            }
        }

        if (IS_WINDOWS) {
            for (String winPath : List.of(
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg.exe"
            )) {
                if (Files.isExecutable(Path.of(winPath))) {
                    return winPath;
                }
            }
        }

        return null;
    }

    // --- Helpers ---

    private static String noDevicesMessage() {
        if (IS_WINDOWS) {
            return "No DirectShow camera devices were detected. Make sure your webcam is connected and drivers are installed.";
        }
        if (IS_MAC) {
            return "No AVFoundation camera devices were detected. If you just granted permission, restart the client and press Refresh.";
        }
        return "No V4L2 camera devices were detected (/dev/videoX). Make sure your webcam is connected.";
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

    // --- Session ---

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
