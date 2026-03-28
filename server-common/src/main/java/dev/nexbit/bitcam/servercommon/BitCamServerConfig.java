package dev.nexbit.bitcam.servercommon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record BitCamServerConfig(
    String udpHost,
    int udpPort,
    int radius,
    int width,
    int height,
    int fps,
    float quality,
    int mtu
) {
    private static final String FILE_NAME = "bitcam-server.properties";

    public static BitCamServerConfig load(Path configDirectory) {
        try {
            Files.createDirectories(configDirectory);
            Path file = configDirectory.resolve(FILE_NAME);

            Properties properties = new Properties();
            if (Files.exists(file)) {
                try (InputStream input = Files.newInputStream(file)) {
                    properties.load(input);
                }
            }

            BitCamServerConfig config = new BitCamServerConfig(
                properties.getProperty("udp.host", "127.0.0.1"),
                Integer.parseInt(properties.getProperty("udp.port", "35475")),
                Integer.parseInt(properties.getProperty("stream.radius", "24")),
                Integer.parseInt(properties.getProperty("frame.width", "320")),
                Integer.parseInt(properties.getProperty("frame.height", "180")),
                Integer.parseInt(properties.getProperty("frame.fps", "15")),
                Float.parseFloat(properties.getProperty("frame.quality", "0.92")),
                Integer.parseInt(properties.getProperty("frame.mtu", "1200"))
            );

            if (!Files.exists(file)) {
                config.save(file);
            }

            return config;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load BitCam server config", exception);
        }
    }

    public void save(Path file) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("udp.host", this.udpHost);
        properties.setProperty("udp.port", Integer.toString(this.udpPort));
        properties.setProperty("stream.radius", Integer.toString(this.radius));
        properties.setProperty("frame.width", Integer.toString(this.width));
        properties.setProperty("frame.height", Integer.toString(this.height));
        properties.setProperty("frame.fps", Integer.toString(this.fps));
        properties.setProperty("frame.quality", Float.toString(this.quality));
        properties.setProperty("frame.mtu", Integer.toString(this.mtu));

        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "BitCam server media settings");
        }
    }
}
