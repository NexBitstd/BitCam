package dev.nexbit.bitcam.protocol.udp;

import java.util.UUID;

public record SessionRequestUdpPacket(
    int protocolVersion,
    UUID sessionId,
    byte[] secret,
    boolean sendEnabled,
    boolean receiveEnabled
) implements BitCamUdpPacket {
    @Override
    public UdpPacketType type() {
        return UdpPacketType.SESSION_REQUEST;
    }
}
