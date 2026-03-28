package dev.nexbit.bitcam.servercommon;

import java.util.List;

public record BitCamServerDebugSnapshot(
    String udpHost,
    int udpPort,
    int mtu,
    int width,
    int height,
    int fps,
    float quality,
    int radius,
    int sessionCount,
    int activeReceiverCount,
    int activeStreamCount,
    List<BitCamClientDebugSnapshot> clients
) {
}
