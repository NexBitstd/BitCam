package dev.nexbit.bitcam.protocol.udp;

import java.util.UUID;

/**
 * Sent by a viewer to ask a streamer for a fresh keyframe (IDR) — needed when joining mid-stream or
 * after packet loss corrupts an inter-frame stream. The server validates {@link #sessionId} (the
 * requester) and relays it to the {@link #streamerId}'s session.
 */
public record KeyframeRequestUdpPacket(UUID sessionId, UUID streamerId) implements BitCamUdpPacket {
    @Override
    public UdpPacketType type() {
        return UdpPacketType.KEYFRAME_REQUEST;
    }
}
