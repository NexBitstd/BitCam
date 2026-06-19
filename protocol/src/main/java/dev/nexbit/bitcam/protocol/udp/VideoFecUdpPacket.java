package dev.nexbit.bitcam.protocol.udp;

import java.util.UUID;

/**
 * Forward-error-correction parity for a contiguous group of a frame's data fragments. The
 * {@link #parity} is the XOR of every data shard in {@code [groupStart, groupStart + groupShardCount)}
 * (each zero-padded to {@link #shardLength}), letting a viewer reconstruct exactly one missing shard
 * in the group without a retransmission.
 *
 * <p>{@link #totalPayloadLength} is the full frame payload length, needed to recover the true length
 * of the final (short) shard and to truncate the reassembled buffer.
 */
public record VideoFecUdpPacket(
    UUID streamerId,
    int frameId,
    int groupStart,
    int groupShardCount,
    int shardLength,
    int totalPayloadLength,
    byte[] parity
) implements BitCamUdpPacket {
    @Override
    public UdpPacketType type() {
        return UdpPacketType.VIDEO_FEC;
    }
}
