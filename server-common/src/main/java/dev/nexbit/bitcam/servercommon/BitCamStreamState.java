package dev.nexbit.bitcam.servercommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import dev.nexbit.bitcam.protocol.udp.VideoFrameUdpPacket;

final class BitCamStreamState {
    private volatile int width;
    private volatile int height;
    private volatile int frameId;
    private volatile int fragmentCount;
    private volatile boolean keyFrame;
    private volatile int viewerCount;
    private volatile long captureTimeMillis;
    private volatile long lastPacketTimeMillis;
    private volatile BitCamBubbleStyle bubbleStyle = BitCamBubbleStyle.DEFAULT;

    void update(VideoFrameUdpPacket packet, int viewerCount) {
        this.width = packet.width();
        this.height = packet.height();
        this.frameId = packet.frameId();
        this.fragmentCount = packet.fragmentCount();
        this.keyFrame = packet.keyFrame();
        this.viewerCount = viewerCount;
        this.captureTimeMillis = packet.captureTimeMillis();
        this.lastPacketTimeMillis = System.currentTimeMillis();
        this.bubbleStyle = packet.bubbleStyle();
    }

    BitCamStreamDebugSnapshot snapshot(long now) {
        return new BitCamStreamDebugSnapshot(
            this.width,
            this.height,
            this.frameId,
            this.fragmentCount,
            this.keyFrame,
            this.viewerCount,
            Math.max(0L, now - this.captureTimeMillis),
            Math.max(0L, now - this.lastPacketTimeMillis),
            this.bubbleStyle
        );
    }
}
