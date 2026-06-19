package dev.nexbit.bitcam.protocol.udp;

import java.util.UUID;

public record VideoFrameUdpPacket(
    UUID streamerId,
    int frameId,
    int fragmentIndex,
    int fragmentCount,
    int width,
    int height,
    long captureTimeMillis,
    BitCamBubbleStyle bubbleStyle,
    boolean keyFrame,
    BitCamVideoCodec codec,
    byte[] payload
) implements BitCamUdpPacket {
    @Override
    public UdpPacketType type() {
        return UdpPacketType.VIDEO_FRAME;
    }
}
