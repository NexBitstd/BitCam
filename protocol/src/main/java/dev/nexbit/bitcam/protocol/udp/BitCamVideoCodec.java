package dev.nexbit.bitcam.protocol.udp;

/**
 * Identifies how a {@link VideoFrameUdpPacket} payload is encoded, so the receiver picks the right
 * decoder. Carried per packet because each streamer may use a different codec.
 */
public enum BitCamVideoCodec {
    /** Motion-JPEG: every frame is an independent JPEG. Loss-tolerant, higher bandwidth. */
    MJPEG,
    /** H.264 (libx264): inter-frame coded. Far lower bandwidth, needs keyframes to recover. */
    H264
}
