package dev.nexbit.bitcam.clientcommon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Detects whether the platform-specific JavaCV/FFmpeg native JARs are already on the classpath
 * (dev environment) or need to be downloaded from Maven Central (released mod build).
 *
 * Versions here must stay in sync with gradle.properties (javacv_version, bytedeco_ffmpeg_version).
 */
public final class CameraLibraryManager {
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    private static final String JAVACV_VERSION = "1.5.12";
    private static final String FFMPEG_VERSION = "7.1.1-1.5.12";
    private static final int BUFFER_SIZE = 64 * 1024;

    public enum Status { CHECKING, NOT_NEEDED, DOWNLOADING, READY, FAILED }

    // The GPL FFmpeg build (the only one that bundles the libx264 encoder we need for H.264) ships its
    // natives under the "-gpl" platform extension. javacpp must be told to look there or it will only
    // search the plain platform folder and fail to find the encoder libraries.
    private static final String FFMPEG_PLATFORM_EXTENSION = "-gpl";

    static {
        // Must be set before any javacpp Loader initializes; this class is touched (ensureReady) well
        // before the first FFmpeg call, so the property is in place in time.
        if (System.getProperty("org.bytedeco.javacpp.platform.extension") == null) {
            System.setProperty("org.bytedeco.javacpp.platform.extension", FFMPEG_PLATFORM_EXTENSION);
        }
    }

    private static volatile Status status = Status.CHECKING;
    private static volatile String failureMessage = "";
    private static volatile int downloadProgressPercent = 0;
    private static volatile URLClassLoader nativeLoader;

    private CameraLibraryManager() {}

    public static Status status() {
        return status;
    }

    public static int downloadProgressPercent() {
        return downloadProgressPercent;
    }

    public static String failureMessage() {
        return failureMessage;
    }

    public static boolean isReady() {
        Status s = status;
        return s == Status.NOT_NEEDED || s == Status.READY;
    }

    public static boolean isDownloading() {
        return status == Status.DOWNLOADING;
    }

    /**
     * Synchronously ensures native libraries are available.
     * Blocks until download completes if download is needed.
     * Must be called from a background thread (not the game thread).
     */
    public static void ensureReady(Path cacheDir) {
        failureMessage = "";
        if (isNativeOnClasspath()) {
            status = Status.NOT_NEEDED;
            downloadProgressPercent = 100;
            return;
        }

        // Try cached JARs from a previous session
        try {
            String classifier = detectClassifier();
            String ffmpegClassifier = detectFfmpegClassifier();
            Path libsDir = cacheDir.resolve("camera-libs");
            Path javacppJar = libsDir.resolve("javacpp-" + JAVACV_VERSION + "-" + classifier + ".jar");
            Path ffmpegJar = libsDir.resolve("ffmpeg-" + FFMPEG_VERSION + "-" + ffmpegClassifier + ".jar");

            if (Files.exists(javacppJar) && Files.exists(ffmpegJar)) {
                buildLoader(javacppJar, ffmpegJar);
                status = Status.READY;
                downloadProgressPercent = 100;
                return;
            }
        } catch (Exception ignored) {
        }

        // Need to download
        status = Status.DOWNLOADING;
        downloadProgressPercent = 0;

        try {
            String classifier = detectClassifier();
            String ffmpegClassifier = detectFfmpegClassifier();
            Path libsDir = cacheDir.resolve("camera-libs");
            Files.createDirectories(libsDir);

            Path javacppJar = download(libsDir,
                "org/bytedeco/javacpp/" + JAVACV_VERSION,
                "javacpp-" + JAVACV_VERSION + "-" + classifier + ".jar",
                0, 10);
            Path ffmpegJar = download(libsDir,
                "org/bytedeco/ffmpeg/" + FFMPEG_VERSION,
                "ffmpeg-" + FFMPEG_VERSION + "-" + ffmpegClassifier + ".jar",
                10, 98);

            buildLoader(javacppJar, ffmpegJar);
            status = Status.READY;
            downloadProgressPercent = 100;
        } catch (Exception exception) {
            status = Status.FAILED;
            failureMessage = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Failed to download camera libraries."
                : exception.getMessage();
        }
    }

    /**
     * Sets the downloaded native JARs as the context classloader on the current thread
     * so JavaCPP can find and extract native libraries from them.
     * Only has effect when natives were downloaded (not when bundled).
     */
    public static void applyToThread() {
        URLClassLoader loader = nativeLoader;
        if (loader != null) {
            Thread.currentThread().setContextClassLoader(loader);
        }
    }

    public static String detectClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");

        if (os.contains("win")) return "windows-x86_64";
        if (os.contains("mac")) return isArm ? "macosx-arm64" : "macosx-x86_64";
        return isArm ? "linux-arm64" : "linux-x86_64";
    }

    /** The ffmpeg native JAR uses the GPL variant (with libx264); javacpp itself has no GPL build. */
    public static String detectFfmpegClassifier() {
        return detectClassifier() + FFMPEG_PLATFORM_EXTENSION;
    }

    private static boolean isNativeOnClasspath() {
        String ffmpegClassifier = detectFfmpegClassifier();
        boolean isWindows = ffmpegClassifier.startsWith("windows");
        // This resource exists only in the platform-specific (GPL) ffmpeg native JAR.
        String resource = "org/bytedeco/ffmpeg/" + ffmpegClassifier + "/" + (isWindows ? "ffmpeg.exe" : "ffmpeg");
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx.getResource(resource) != null
            || CameraLibraryManager.class.getClassLoader().getResource(resource) != null;
    }

    private static void buildLoader(Path javacppJar, Path ffmpegJar) throws Exception {
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        nativeLoader = new URLClassLoader(
            new URL[]{javacppJar.toUri().toURL(), ffmpegJar.toUri().toURL()},
            parent
        );
    }

    private static Path download(
        Path dir,
        String groupVersionPath,
        String fileName,
        int progressStart,
        int progressEnd
    ) throws IOException {
        Path target = dir.resolve(fileName);
        if (Files.exists(target)) {
            downloadProgressPercent = progressEnd;
            return target;
        }

        Path temp = dir.resolve(fileName + ".part");
        String urlString = MAVEN_CENTRAL + groupVersionPath + "/" + fileName;

        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent", "BitCam/1.0");

            long total = connection.getContentLengthLong();
            long received = 0;

            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temp,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    received += read;
                    if (total > 0) {
                        float fraction = (float) received / total;
                        downloadProgressPercent = progressStart + Math.round(fraction * (progressEnd - progressStart));
                    }
                }
            } finally {
                connection.disconnect();
            }

            Files.move(temp, target);
            downloadProgressPercent = progressEnd;
            return target;
        } catch (IOException exception) {
            Files.deleteIfExists(temp);
            throw new IOException("Failed to download " + fileName + ": " + exception.getMessage(), exception);
        }
    }
}
