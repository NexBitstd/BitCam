package dev.nexbit.bitcam.protocol.signal;

import java.util.List;
import java.util.UUID;

public record ServerWelcomeSignalPacket(
    int protocolVersion,
    String udpHost,
    int udpPort,
    UUID sessionId,
    byte[] secret,
    int mtu,
    int width,
    int height,
    int fps,
    float quality,
    String selectedQualityProfileId,
    List<BitCamStreamQualityProfile> availableQualityProfiles,
    int radius
) implements BitCamSignalPacket {
    public ServerWelcomeSignalPacket {
        selectedQualityProfileId = selectedQualityProfileId == null ? "" : selectedQualityProfileId.trim();
        availableQualityProfiles = availableQualityProfiles == null ? List.of() : List.copyOf(availableQualityProfiles);
    }

    @Override
    public BitCamSignalPacketType type() {
        return BitCamSignalPacketType.SERVER_WELCOME;
    }
}
