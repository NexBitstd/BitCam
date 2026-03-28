package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.StreamStoppedUdpPacket;
import dev.nexbit.bitcam.protocol.udp.VideoFrameUdpPacket;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RemoteFrameStore {
    private final Map<UUID, RemoteFrame> frames = new ConcurrentHashMap<>();
    private final Map<String, PendingFrame> pendingFrames = new ConcurrentHashMap<>();

    public void accept(VideoFrameUdpPacket packet) {
        String key = packet.streamerId() + ":" + packet.frameId();
        PendingFrame pending = this.pendingFrames.computeIfAbsent(
            key,
            ignored -> new PendingFrame(
                packet.streamerId(),
                packet.frameId(),
                packet.width(),
                packet.height(),
                packet.captureTimeMillis(),
                packet.bubbleStyle(),
                packet.fragmentCount()
            )
        );

        pending.fragments.put(packet.fragmentIndex(), packet.payload());

        if (pending.fragments.size() == pending.fragmentCount) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (int index = 0; index < pending.fragmentCount; index++) {
                byte[] fragment = pending.fragments.get(index);
                if (fragment == null) {
                    return;
                }
                output.writeBytes(fragment);
            }

            this.frames.put(
                packet.streamerId(),
                new RemoteFrame(
                    packet.streamerId(),
                    packet.frameId(),
                    packet.width(),
                    packet.height(),
                    packet.captureTimeMillis(),
                    pending.bubbleStyle,
                    output.toByteArray(),
                    System.currentTimeMillis()
                )
            );
            this.pendingFrames.remove(key);
        }
    }

    public void accept(StreamStoppedUdpPacket packet) {
        this.frames.remove(packet.streamerId());
    }

    public RemoteFrame frame(UUID streamerId) {
        return this.frames.get(streamerId);
    }

    public Map<UUID, RemoteFrame> snapshot() {
        return Map.copyOf(this.frames);
    }

    public void pruneExpired(long maxAgeMillis) {
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        this.frames.entrySet().removeIf(entry -> entry.getValue().receivedAtMillis() < cutoff);
    }

    private static final class PendingFrame {
        private final UUID streamerId;
        private final int frameId;
        private final int width;
        private final int height;
        private final long captureTimeMillis;
        private final dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle bubbleStyle;
        private final int fragmentCount;
        private final Map<Integer, byte[]> fragments = new ConcurrentHashMap<>();

        private PendingFrame(
            UUID streamerId,
            int frameId,
            int width,
            int height,
            long captureTimeMillis,
            dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle bubbleStyle,
            int fragmentCount
        ) {
            this.streamerId = streamerId;
            this.frameId = frameId;
            this.width = width;
            this.height = height;
            this.captureTimeMillis = captureTimeMillis;
            this.bubbleStyle = bubbleStyle;
            this.fragmentCount = fragmentCount;
        }
    }
}
