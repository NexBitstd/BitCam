package dev.nexbit.bitcam.protocol.udp;

public enum UdpPacketType {
    SESSION_REQUEST,
    SESSION_ACCEPTED,
    KEEP_ALIVE,
    VIDEO_FRAME,
    STREAM_STOPPED,
    KEYFRAME_REQUEST,
    RECEIVER_REPORT,
    VIDEO_FEC
}
