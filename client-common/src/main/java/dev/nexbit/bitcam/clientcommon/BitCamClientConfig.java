package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubblePreset;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleContentMode;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleRenderMode;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleShape;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class BitCamClientConfig {
    private static final String FILE_NAME = "bitcam-client.properties";
    private static final String OFFSETS_SIGNED_KEY = "bubble.offsets_signed";
    private static final String BUBBLE_OFFSET_RANGE_KEY = "bubble.offset_range";
    private static final int LEGACY_BUBBLE_OFFSET_RANGE = 100;

    private final Path file;
    private String preferredCameraName;
    private CameraCaptureMode preferredCaptureMode;
    private BitCamBubbleStyle bubbleStyle;
    private final Set<UUID> hiddenPlayerIds;
    private boolean setupCompleted;

    private BitCamClientConfig(
        Path file,
        String preferredCameraName,
        CameraCaptureMode preferredCaptureMode,
        BitCamBubbleStyle bubbleStyle,
        Set<UUID> hiddenPlayerIds,
        boolean setupCompleted
    ) {
        this.file = file;
        this.preferredCameraName = preferredCameraName;
        this.preferredCaptureMode = preferredCaptureMode;
        this.bubbleStyle = bubbleStyle;
        this.hiddenPlayerIds = hiddenPlayerIds;
        this.setupCompleted = setupCompleted;
    }

    public static BitCamClientConfig load(Path configDirectory) {
        try {
            Files.createDirectories(configDirectory);
            Path file = configDirectory.resolve(FILE_NAME);
            Properties properties = new Properties();
            if (Files.exists(file)) {
                try (InputStream input = Files.newInputStream(file)) {
                    properties.load(input);
                }
            }

            boolean signedOffsets = Boolean.parseBoolean(properties.getProperty(OFFSETS_SIGNED_KEY, "false"));
            int storedOffsetRange = readInt(properties, BUBBLE_OFFSET_RANGE_KEY, LEGACY_BUBBLE_OFFSET_RANGE);
            String preferredCameraName = properties.getProperty("camera.preferred", "");
            int storedBubbleX = readInt(properties, "bubble.x_offset_percent", signedOffsets ? 0 : 100);
            int storedBubbleY = readInt(properties, "bubble.y_offset_percent", signedOffsets ? 0 : 100);

            BitCamClientConfig config = new BitCamClientConfig(
                file,
                preferredCameraName,
                CameraCaptureMode.fromSerialized(properties.getProperty("camera.capture_mode", ""), CameraCaptureMode.AUTO),
                new BitCamBubbleStyle(
                    BitCamBubblePreset.fromSerialized(properties.getProperty("bubble.preset", BitCamBubblePreset.CLASSIC.name())),
                    BitCamBubbleShape.fromSerialized(properties.getProperty("bubble.shape", BitCamBubbleShape.RECTANGLE.name())),
                    BitCamBubbleRenderMode.fromSerialized(properties.getProperty("bubble.render_mode", BitCamBubbleRenderMode.BILLBOARD.name())),
                    readInt(properties, "bubble.scale_percent", 100),
                    normalizeStoredBubbleOffset(storedBubbleX, signedOffsets, storedOffsetRange),
                    normalizeStoredBubbleOffset(storedBubbleY, signedOffsets, storedOffsetRange),
                    readInt(properties, "bubble.opacity_percent", 94),
                    BitCamBubbleContentMode.fromSerialized(properties.getProperty("bubble.content_mode", BitCamBubbleContentMode.COVER.name())),
                    readInt(properties, "bubble.content_zoom_percent", 100),
                    readInt(properties, "bubble.content_x_offset_percent", 100),
                    readInt(properties, "bubble.content_y_offset_percent", 100)
                ),
                readUuidSet(properties.getProperty("players.hidden", "")),
                readBoolean(properties, "setup.completed", !preferredCameraName.isBlank())
            );
            if (!Files.exists(file)) {
                config.save();
            }
            return config;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load BitCam client config", exception);
        }
    }

    public String preferredCameraName() {
        return this.preferredCameraName;
    }

    public void preferredCameraName(String preferredCameraName) {
        this.preferredCameraName = preferredCameraName == null ? "" : preferredCameraName;
        this.save();
    }

    public CameraCaptureMode preferredCaptureMode() {
        return this.preferredCaptureMode;
    }

    public void preferredCaptureMode(CameraCaptureMode preferredCaptureMode) {
        this.preferredCaptureMode = preferredCaptureMode == null ? CameraCaptureMode.AUTO : preferredCaptureMode;
        this.save();
    }

    public BitCamBubbleStyle bubbleStyle() {
        return this.bubbleStyle;
    }

    public void bubbleStyle(BitCamBubbleStyle bubbleStyle) {
        this.bubbleStyle = bubbleStyle == null ? BitCamBubbleStyle.DEFAULT : bubbleStyle;
        this.save();
    }

    public boolean isPlayerHidden(UUID playerId) {
        return playerId != null && this.hiddenPlayerIds.contains(playerId);
    }

    public void setPlayerHidden(UUID playerId, boolean hidden) {
        if (playerId == null) {
            return;
        }

        if (hidden) {
            this.hiddenPlayerIds.add(playerId);
        } else {
            this.hiddenPlayerIds.remove(playerId);
        }
        this.save();
    }

    public Set<UUID> hiddenPlayerIds() {
        return Set.copyOf(this.hiddenPlayerIds);
    }

    public boolean setupCompleted() {
        return this.setupCompleted;
    }

    public void setupCompleted(boolean setupCompleted) {
        this.setupCompleted = setupCompleted;
        this.save();
    }

    private void save() {
        try {
            Properties properties = new Properties();
            properties.setProperty("camera.preferred", this.preferredCameraName);
            properties.setProperty("camera.capture_mode", this.preferredCaptureMode.serialized());
            properties.setProperty("bubble.preset", this.bubbleStyle.preset().name());
            properties.setProperty("bubble.shape", this.bubbleStyle.shape().name());
            properties.setProperty("bubble.render_mode", this.bubbleStyle.renderMode().name());
            properties.setProperty("bubble.scale_percent", Integer.toString(this.bubbleStyle.scalePercent()));
            properties.setProperty(OFFSETS_SIGNED_KEY, "true");
            properties.setProperty(BUBBLE_OFFSET_RANGE_KEY, Integer.toString(BitCamBubbleStyle.BUBBLE_OFFSET_MAX));
            properties.setProperty("bubble.x_offset_percent", Integer.toString(this.bubbleStyle.xOffsetPercent()));
            properties.setProperty("bubble.y_offset_percent", Integer.toString(this.bubbleStyle.yOffsetPercent()));
            properties.setProperty("bubble.opacity_percent", Integer.toString(this.bubbleStyle.opacityPercent()));
            properties.setProperty("bubble.content_mode", this.bubbleStyle.contentMode().name());
            properties.setProperty("bubble.content_zoom_percent", Integer.toString(this.bubbleStyle.contentZoomPercent()));
            properties.setProperty("bubble.content_x_offset_percent", Integer.toString(this.bubbleStyle.contentXOffsetPercent()));
            properties.setProperty("bubble.content_y_offset_percent", Integer.toString(this.bubbleStyle.contentYOffsetPercent()));
            properties.setProperty("setup.completed", Boolean.toString(this.setupCompleted));
            properties.setProperty(
                "players.hidden",
                this.hiddenPlayerIds.stream().map(UUID::toString).collect(Collectors.joining(","))
            );
            try (OutputStream output = Files.newOutputStream(this.file)) {
                properties.store(output, "BitCam client media settings");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save BitCam client config", exception);
        }
    }

    private static int readInt(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value.trim());
    }

    private static int normalizeStoredBubbleOffset(int storedValue, boolean signedOffsets, int storedRange) {
        int signedValue = signedOffsets ? storedValue : storedValue - LEGACY_BUBBLE_OFFSET_RANGE;
        int range = Math.max(1, storedRange);
        return Math.round((signedValue / (float) range) * BitCamBubbleStyle.BUBBLE_OFFSET_MAX);
    }

    private static Set<UUID> readUuidSet(String value) {
        LinkedHashSet<UUID> uuids = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return uuids;
        }

        for (String part : value.split(",")) {
            if (part == null || part.isBlank()) {
                continue;
            }

            try {
                uuids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed values and let the next save clean the file.
            }
        }

        return uuids;
    }
}
