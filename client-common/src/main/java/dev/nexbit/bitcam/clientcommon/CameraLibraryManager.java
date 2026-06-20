package dev.nexbit.bitcam.clientcommon;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Detects whether platform-specific JavaCPP/OpenCV native JARs are already on the classpath (dev
 * environment) or need to be downloaded from Maven Central (released mod build).
 *
 * <p>Versions here must stay in sync with gradle.properties (javacpp_version,
 * bytedeco_opencv_version, bytedeco_openblas_version).
 */
public final class CameraLibraryManager {
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    private static final String JAVACPP_VERSION = "1.5.12";
    private static final String OPENCV_VERSION = "4.11.0-1.5.12";
    private static final String OPENBLAS_VERSION = "0.3.30-1.5.12";
    private static final int BUFFER_SIZE = 64 * 1024;

    public enum Status { CHECKING, NOT_NEEDED, DOWNLOADING, READY, FAILED }

    private static volatile Status status = Status.CHECKING;
    private static volatile String failureMessage = "";
    private static volatile int downloadProgressPercent = 0;

    private CameraLibraryManager() {
    }

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

        try {
            String classifier = detectClassifier();
            Path libsDir = cacheDir.resolve("camera-libs");
            Path javacppJar = libsDir.resolve("javacpp-" + JAVACPP_VERSION + "-" + classifier + ".jar");
            Path openblasJar = libsDir.resolve("openblas-" + OPENBLAS_VERSION + "-" + classifier + ".jar");
            Path opencvJar = libsDir.resolve("opencv-" + OPENCV_VERSION + "-" + classifier + ".jar");

            if (Files.exists(javacppJar) && Files.exists(openblasJar) && Files.exists(opencvJar)) {
                configureDownloadedLibraries(libsDir, javacppJar, openblasJar, opencvJar, classifier);
                status = Status.READY;
                downloadProgressPercent = 100;
                return;
            }
        } catch (Exception ignored) {
        }

        status = Status.DOWNLOADING;
        downloadProgressPercent = 0;

        try {
            String classifier = detectClassifier();
            Path libsDir = cacheDir.resolve("camera-libs");
            Files.createDirectories(libsDir);

            Path javacppJar = download(libsDir,
                "org/bytedeco/javacpp/" + JAVACPP_VERSION,
                "javacpp-" + JAVACPP_VERSION + "-" + classifier + ".jar",
                0, 5);
            Path openblasJar = download(libsDir,
                "org/bytedeco/openblas/" + OPENBLAS_VERSION,
                "openblas-" + OPENBLAS_VERSION + "-" + classifier + ".jar",
                5, 20);
            Path opencvJar = download(libsDir,
                "org/bytedeco/opencv/" + OPENCV_VERSION,
                "opencv-" + OPENCV_VERSION + "-" + classifier + ".jar",
                20, 98);

            configureDownloadedLibraries(libsDir, javacppJar, openblasJar, opencvJar, classifier);
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
     * Downloaded native libraries are exposed through JavaCPP path properties because JavaCPP resolves
     * resources through generated OpenCV classloaders.
     */
    public static void applyToThread() {
    }

    public static String detectClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");

        if (os.contains("win")) return "windows-x86_64";
        if (os.contains("mac")) return isArm ? "macosx-arm64" : "macosx-x86_64";
        return isArm ? "linux-arm64" : "linux-x86_64";
    }

    private static boolean isNativeOnClasspath() {
        String classifier = detectClassifier();
        String openCvResource = "org/bytedeco/opencv/" + classifier + "/";
        String openBlasResource = "org/bytedeco/openblas/" + classifier + "/";
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        ClassLoader own = CameraLibraryManager.class.getClassLoader();
        return (ctx.getResource(openCvResource) != null || own.getResource(openCvResource) != null)
            && (ctx.getResource(openBlasResource) != null || own.getResource(openBlasResource) != null);
    }

    private static void configureDownloadedLibraries(
        Path libsDir,
        Path javacppJar,
        Path openblasJar,
        Path opencvJar,
        String classifier
    ) throws IOException {
        Path extractedDir = libsDir.resolve("extracted");
        Path javacppNativeDir = extractNativeDirectory(
            javacppJar,
            extractedDir.resolve(stripJarSuffix(javacppJar.getFileName().toString())),
            "org/bytedeco/javacpp/" + classifier + "/"
        );
        Path openblasNativeDir = extractNativeDirectory(
            openblasJar,
            extractedDir.resolve(stripJarSuffix(openblasJar.getFileName().toString())),
            "org/bytedeco/openblas/" + classifier + "/"
        );
        Path opencvNativeDir = extractNativeDirectory(
            opencvJar,
            extractedDir.resolve(stripJarSuffix(opencvJar.getFileName().toString())),
            "org/bytedeco/opencv/" + classifier + "/"
        );

        String nativePaths = javacppNativeDir.toAbsolutePath()
            + File.pathSeparator
            + openblasNativeDir.toAbsolutePath()
            + File.pathSeparator
            + opencvNativeDir.toAbsolutePath();
        prependPathProperty("platform.preloadpath", nativePaths);
        prependPathProperty("platform.linkpath", nativePaths);
    }

    private static Path extractNativeDirectory(Path jar, Path targetDir, String entryPrefix) throws IOException {
        Path marker = targetDir.resolve(".complete");
        if (Files.exists(marker)) {
            return targetDir;
        }

        Files.createDirectories(targetDir);
        try (JarInputStream input = new JarInputStream(Files.newInputStream(jar))) {
            JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().startsWith(entryPrefix)) {
                    continue;
                }
                String relativeName = entry.getName().substring(entryPrefix.length());
                if (relativeName.isBlank()) {
                    continue;
                }
                Path output = targetDir.resolve(relativeName).normalize();
                if (!output.startsWith(targetDir)) {
                    continue;
                }
                Files.createDirectories(output.getParent());
                Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                output.toFile().setExecutable(true, false);
            }
        }
        Files.writeString(marker, "ok", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return targetDir;
    }

    private static void prependPathProperty(String key, String paths) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String path : paths.split(File.pathSeparator)) {
            if (!path.isBlank()) {
                values.add(path);
            }
        }

        String existing = System.getProperty(key, "");
        if (!existing.isBlank()) {
            for (String path : existing.split(File.pathSeparator)) {
                if (!path.isBlank()) {
                    values.add(path);
                }
            }
        }

        System.setProperty(key, String.join(File.pathSeparator, new ArrayList<>(values)));
    }

    private static String stripJarSuffix(String fileName) {
        return fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
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
