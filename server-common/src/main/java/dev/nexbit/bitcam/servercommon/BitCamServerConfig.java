package dev.nexbit.bitcam.servercommon;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.nexbit.bitcam.protocol.signal.BitCamStreamQualityProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record BitCamServerConfig(
    String udpHost,
    int udpPort,
    int radius,
    List<BitCamServerQualityPreset> qualityPresets,
    String defaultQualityPresetId,
    int mtu
) {
    private static final String FILE_NAME = "bitcam-server.toml";

    public BitCamServerConfig {
        udpHost = udpHost == null || udpHost.isBlank() ? "127.0.0.1" : udpHost.trim();
        udpPort = Math.max(1, udpPort);
        radius = Math.max(1, radius);
        qualityPresets = qualityPresets == null || qualityPresets.isEmpty() ? defaultPresets() : List.copyOf(qualityPresets);
        defaultQualityPresetId = normalizeDefaultQualityPresetId(defaultQualityPresetId, qualityPresets);
        mtu = Math.max(512, mtu);
    }

    public static BitCamServerConfig load(Path configDirectory) {
        try {
            Files.createDirectories(configDirectory);
            Path file = configDirectory.resolve(FILE_NAME);
            boolean fileExists = Files.exists(file);

            if (!fileExists) {
                BitCamServerConfig defaults = new BitCamServerConfig("127.0.0.1", 35475, 24, defaultPresets(), "standard", 1200);
                defaults.save(file);
                return defaults;
            }

            try (CommentedFileConfig config = CommentedFileConfig.builder(file).build()) {
                config.load();

                String udpHost = config.getOrElse("network.udp_host", "127.0.0.1");
                int udpPort = config.getIntOrElse("network.udp_port", 35475);
                int radius = config.getIntOrElse("stream.radius", 24);
                int mtu = config.getIntOrElse("stream.mtu", 1200);
                String defaultQualityPresetId = config.getOrElse("stream.default_quality", "standard");
                List<BitCamServerQualityPreset> presets = readQualityPresets(config);

                if (presets.isEmpty()) {
                    presets = defaultPresets();
                }

                return new BitCamServerConfig(udpHost, udpPort, radius, presets, defaultQualityPresetId, mtu);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load BitCam server config", exception);
        }
    }

    public BitCamStreamQualityProfile defaultQualityProfile() {
        return this.qualityPresets.stream()
            .map(BitCamServerQualityPreset::profile)
            .filter(profile -> profile.id().equals(this.defaultQualityPresetId))
            .findFirst()
            .orElseGet(() -> this.qualityPresets.getFirst().profile());
    }

    public int width() { return this.defaultQualityProfile().width(); }
    public int height() { return this.defaultQualityProfile().height(); }
    public int fps() { return this.defaultQualityProfile().fps(); }
    public float quality() { return this.defaultQualityProfile().quality(); }

    public void save(Path file) throws IOException {
        try (CommentedFileConfig config = CommentedFileConfig.builder(file).build()) {
            config.setComment("network", " Network settings");
            config.set("network.udp_host", this.udpHost);
            config.set("network.udp_port", this.udpPort);

            config.setComment("stream", " Stream settings");
            config.set("stream.radius", this.radius);
            config.set("stream.mtu", this.mtu);
            config.set("stream.default_quality", this.defaultQualityPresetId);

            List<CommentedConfig> presetConfigs = new ArrayList<>();
            for (BitCamServerQualityPreset preset : this.qualityPresets) {
                CommentedConfig entry = config.createSubConfig();
                entry.set("id", preset.profile().id());
                entry.set("name", preset.profile().displayName());
                entry.set("width", preset.profile().width());
                entry.set("height", preset.profile().height());
                entry.set("fps", preset.profile().fps());
                entry.set("quality", (double) preset.profile().quality());
                entry.set("permission", preset.permissionExpression());
                presetConfigs.add(entry);
            }
            config.set("quality_preset", presetConfigs);

            config.save();
        }
    }

    private static List<BitCamServerQualityPreset> readQualityPresets(CommentedFileConfig config) {
        List<CommentedConfig> presetEntries = config.getOrElse("quality_preset", List.of());
        if (presetEntries.isEmpty()) {
            return List.of();
        }

        List<BitCamServerQualityPreset> presets = new ArrayList<>();
        for (CommentedConfig entry : presetEntries) {
            String id = normalizeId(entry.getOrElse("id", ""));
            if (id.isEmpty()) {
                continue;
            }
            presets.add(new BitCamServerQualityPreset(
                new BitCamStreamQualityProfile(
                    id,
                    entry.getOrElse("name", prettifyName(id)),
                    entry.getIntOrElse("width", 320),
                    entry.getIntOrElse("height", 180),
                    entry.getIntOrElse("fps", 15),
                    ((Number) entry.getOrElse("quality", 0.92)).floatValue()
                ),
                entry.getOrElse("permission", "")
            ));
        }
        return List.copyOf(presets);
    }

    // The bubble is drawn small and the viewer caps its texture at 480p, so streaming higher than that
    // only burns encode/network/decode for no visible gain. These three cover the useful range; 480p@30
    // is the practical ceiling, and 60fps isn't worth it for a talking-head bubble.
    private static List<BitCamServerQualityPreset> defaultPresets() {
        return List.of(
            new BitCamServerQualityPreset(new BitCamStreamQualityProfile("low", "Low", 320, 180, 24, 0.85F), ""),
            new BitCamServerQualityPreset(new BitCamStreamQualityProfile("standard", "Standard", 640, 360, 30, 0.92F), ""),
            new BitCamServerQualityPreset(new BitCamStreamQualityProfile("high", "High", 854, 480, 30, 0.95F), "")
        );
    }

    private static String normalizeDefaultQualityPresetId(String defaultQualityPresetId, List<BitCamServerQualityPreset> presets) {
        String normalized = normalizeId(defaultQualityPresetId);
        for (BitCamServerQualityPreset preset : presets) {
            if (preset.profile().id().equals(normalized)) {
                return normalized;
            }
        }
        for (BitCamServerQualityPreset preset : presets) {
            if ("sd".equals(preset.profile().id())) {
                return preset.profile().id();
            }
        }
        return presets.getFirst().profile().id();
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            return "standard";
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return normalized.isEmpty() ? "standard" : normalized;
    }

    private static String prettifyName(String id) {
        String normalized = normalizeId(id).replace('_', ' ');
        if (normalized.isEmpty()) {
            return "Standard";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
