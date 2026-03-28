package dev.nexbit.bitcam.protocol.udp;

import java.util.UUID;

public record StreamStoppedUdpPacket(UUID streamerId) implements BitCamUdpPacket {
    @Override
    public UdpPacketType type() {
        return UdpPacketType.STREAM_STOPPED;
    }
}
