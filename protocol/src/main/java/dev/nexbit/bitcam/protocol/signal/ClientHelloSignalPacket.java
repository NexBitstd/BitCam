package dev.nexbit.bitcam.protocol.signal;

public record ClientHelloSignalPacket(int protocolVersion, String requestedQualityProfileId) implements BitCamSignalPacket {
    public ClientHelloSignalPacket {
        requestedQualityProfileId = requestedQualityProfileId == null ? "" : requestedQualityProfileId.trim();
    }

    @Override
    public BitCamSignalPacketType type() {
        return BitCamSignalPacketType.CLIENT_HELLO;
    }
}
