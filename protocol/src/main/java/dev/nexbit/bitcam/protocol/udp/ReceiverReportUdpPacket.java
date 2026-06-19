package dev.nexbit.bitcam.protocol.udp;

import java.util.UUID;

/**
 * Sent periodically by a viewer to tell a streamer how its stream is arriving, so the streamer can
 * adapt its bitrate to the worst link among its viewers (congestion control).
 *
 * <p>{@link #lossPermille} is the fraction of frames the viewer missed over the last window, in
 * per-mille (0–1000) to keep the wire format integer-only. The server validates {@link #sessionId}
 * (the reporter) and relays the report to the {@link #streamerId}'s session, exactly like a keyframe
 * request.
 */
public record ReceiverReportUdpPacket(UUID sessionId, UUID streamerId, int lossPermille) implements BitCamUdpPacket {
    @Override
    public UdpPacketType type() {
        return UdpPacketType.RECEIVER_REPORT;
    }
}
