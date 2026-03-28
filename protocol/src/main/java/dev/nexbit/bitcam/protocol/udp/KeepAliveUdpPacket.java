package dev.nexbit.bitcam.protocol.udp;

import java.util.UUID;

public record KeepAliveUdpPacket(UUID sessionId) implements BitCamUdpPacket {
    @Override
    public UdpPacketType type() {
        return UdpPacketType.KEEP_ALIVE;
    }
}
