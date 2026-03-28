package dev.nexbit.bitcam.protocol.udp;

import java.util.UUID;

public record SessionAcceptedUdpPacket(UUID sessionId) implements BitCamUdpPacket {
    @Override
    public UdpPacketType type() {
        return UdpPacketType.SESSION_ACCEPTED;
    }
}
