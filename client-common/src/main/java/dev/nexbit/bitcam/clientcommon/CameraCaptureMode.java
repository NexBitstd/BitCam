package dev.nexbit.bitcam.clientcommon;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CameraCaptureMode(int width, int height, int fps) {
    public static final CameraCaptureMode AUTO = new CameraCaptureMode(0, 0, 0);
    public static final CameraCaptureMode DEFAULT = new CameraCaptureMode(1280, 720, 30);
    public static final List<CameraCaptureMode> COMMON_FALLBACKS = List.of(
        DEFAULT,
        new CameraCaptureMode(640, 480, 30),
        new CameraCaptureMode(1920, 1080, 30),
        new CameraCaptureMode(1280, 720, 60)
    );

    private static final Pattern SERIALIZED_PATTERN = Pattern.compile("^(\\d+)x(\\d+)@(\\d+)$");

    public CameraCaptureMode {
        if (width < 0 || height < 0 || fps < 0) {
            throw new IllegalArgumentException("Capture mode values must not be negative");
        }
    }

    public boolean isAuto() {
        return this.width == 0 && this.height == 0 && this.fps == 0;
    }

    public boolean isSpecified() {
        return this.width > 0 && this.height > 0 && this.fps > 0;
    }

    public String serialized() {
        if (!this.isSpecified()) {
            return "";
        }

        return this.width + "x" + this.height + "@" + this.fps;
    }

    public String label() {
        if (!this.isSpecified()) {
            return "Auto";
        }

        return this.width + "x" + this.height + " @ " + this.fps + " FPS";
    }

    public int area() {
        return this.width * this.height;
    }

    public static CameraCaptureMode fromSerialized(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }

        Matcher matcher = SERIALIZED_PATTERN.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return AUTO;
        }

        return new CameraCaptureMode(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))
        );
    }

    public static CameraCaptureMode fromSerialized(String value, CameraCaptureMode defaultValue) {
        CameraCaptureMode parsed = fromSerialized(value);
        if (parsed.isAuto()) {
            return Objects.requireNonNullElse(defaultValue, AUTO);
        }

        return parsed;
    }
}
