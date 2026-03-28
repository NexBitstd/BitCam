package dev.nexbit.bitcam.protocol.signal;

public record ClientHelloSignalPacket(int protocolVersion) implements BitCamSignalPacket {
    @Override
    public BitCamSignalPacketType type() {
        return BitCamSignalPacketType.CLIENT_HELLO;
    }
}
