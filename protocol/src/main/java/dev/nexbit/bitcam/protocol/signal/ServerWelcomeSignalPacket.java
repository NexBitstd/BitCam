package dev.nexbit.bitcam.protocol.signal;

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
    int radius
) implements BitCamSignalPacket {
    @Override
    public BitCamSignalPacketType type() {
        return BitCamSignalPacketType.SERVER_WELCOME;
    }
}
