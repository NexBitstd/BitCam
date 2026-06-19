package dev.nexbit.bitcam.servercommon;

import dev.nexbit.bitcam.common.PlatformAccess;
import dev.nexbit.bitcam.protocol.udp.BitCamUdpCodec;
import dev.nexbit.bitcam.protocol.udp.BitCamUdpPacket;
import dev.nexbit.bitcam.protocol.udp.KeepAliveUdpPacket;
import dev.nexbit.bitcam.protocol.udp.KeyframeRequestUdpPacket;
import dev.nexbit.bitcam.protocol.udp.ReceiverReportUdpPacket;
import dev.nexbit.bitcam.protocol.udp.SessionAcceptedUdpPacket;
import dev.nexbit.bitcam.protocol.udp.SessionRequestUdpPacket;
import dev.nexbit.bitcam.protocol.udp.StreamStoppedUdpPacket;
import dev.nexbit.bitcam.protocol.udp.VideoFecUdpPacket;
import dev.nexbit.bitcam.protocol.udp.VideoFrameUdpPacket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BitCamUdpServer implements AutoCloseable {
    private static final long SESSION_CLEANUP_INTERVAL_NANOS = 1_000_000_000L;

    private final PlatformAccess platform;
    private final BitCamServerConfig config;
    private final BitCamServerCoordinator coordinator;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-udp-server");
        thread.setDaemon(true);
        return thread;
    });

    // Touched only from the single bitcam-udp-server thread, so plain maps/longs are safe here.
    private final Map<UUID, CachedFrameViewers> viewerCacheByStreamer = new HashMap<>();
    private long lastSessionCleanupNanos;

    private DatagramChannel channel;

    BitCamUdpServer(PlatformAccess platform, BitCamServerConfig config, BitCamServerCoordinator coordinator) {
        this.platform = platform;
        this.config = config;
        this.coordinator = coordinator;
    }

    public void start() {
        try {
            this.channel = DatagramChannel.open();
            this.channel.bind(new InetSocketAddress(this.config.udpPort()));
            this.executor.submit(this::listen);
            this.platform.logger().info("BitCam UDP server listening on " + this.config.udpHost() + ":" + this.config.udpPort());
            if ("127.0.0.1".equals(this.config.udpHost()) || "localhost".equalsIgnoreCase(this.config.udpHost())) {
                this.platform.logger().warn(
                    "BitCam udp.host is set to "
                        + this.config.udpHost()
                        + ". Remote clients will not be able to establish UDP media sessions until this is changed to a reachable host/IP."
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start BitCam UDP server", exception);
        }
    }

    private void listen() {
        ByteBuffer buffer = ByteBuffer.allocate(this.config.mtu() * 2);

        while (this.channel != null && this.channel.isOpen()) {
            SocketAddress address = null;
            try {
                buffer.clear();
                address = this.channel.receive(buffer);
                if (address == null) {
                    continue;
                }

                byte[] payload = new byte[buffer.position()];
                buffer.flip();
                buffer.get(payload);

                this.handle(address, BitCamUdpCodec.decode(payload), payload);
                this.maybeCleanupSessions();
            } catch (IOException exception) {
                if (this.channel != null && this.channel.isOpen()) {
                    this.platform.logger().error("BitCam UDP server failed while receiving packets", exception);
                }
            } catch (RuntimeException exception) {
                String causeMessage = exception.getCause() == null ? "" : " | cause=" + exception.getCause().getMessage();
                this.platform.logger().warn("BitCam dropped malformed UDP packet from " + address + ": " + exception.getMessage() + causeMessage);
            }
        }
    }

    private void handle(SocketAddress address, BitCamUdpPacket packet, byte[] encodedBytes) throws IOException {
        if (packet instanceof SessionRequestUdpPacket request) {
            this.handleSessionRequest(address, request);
        } else if (packet instanceof KeepAliveUdpPacket keepAlive) {
            this.handleKeepAlive(address, keepAlive);
        } else if (packet instanceof VideoFrameUdpPacket videoFrame) {
            this.handleVideoFrame(address, videoFrame, encodedBytes);
        } else if (packet instanceof VideoFecUdpPacket videoFec) {
            this.handleVideoFec(address, videoFec, encodedBytes);
        } else if (packet instanceof StreamStoppedUdpPacket stopped) {
            this.handleStreamStopped(address, stopped, encodedBytes);
        } else if (packet instanceof KeyframeRequestUdpPacket keyframeRequest) {
            this.handleKeyframeRequest(address, keyframeRequest, encodedBytes);
        } else if (packet instanceof ReceiverReportUdpPacket receiverReport) {
            this.handleReceiverReport(address, receiverReport, encodedBytes);
        }
    }

    private void handleReceiverReport(SocketAddress address, ReceiverReportUdpPacket packet, byte[] encodedBytes) throws IOException {
        // Same trust model as a keyframe request: the source must own the claimed session, then we
        // relay the loss feedback to the targeted streamer so its encoder can adapt its bitrate.
        BitCamSession reporter = this.coordinator.session(packet.sessionId());
        if (reporter == null || !address.equals(reporter.address())) {
            return;
        }
        BitCamSession streamer = this.coordinator.sessionByPlayer(packet.streamerId());
        if (streamer == null || streamer.address() == null || !streamer.sendEnabled()) {
            return;
        }
        this.send(streamer.address(), encodedBytes);
    }

    private void handleKeyframeRequest(SocketAddress address, KeyframeRequestUdpPacket packet, byte[] encodedBytes) throws IOException {
        // Validate the requester (the source must own the claimed session), then relay the request
        // to the targeted streamer so its encoder can emit a fresh keyframe.
        BitCamSession requester = this.coordinator.session(packet.sessionId());
        if (requester == null || !address.equals(requester.address())) {
            return;
        }
        BitCamSession streamer = this.coordinator.sessionByPlayer(packet.streamerId());
        if (streamer == null || streamer.address() == null || !streamer.sendEnabled()) {
            return;
        }
        this.send(streamer.address(), encodedBytes);
    }

    private void handleSessionRequest(SocketAddress address, SessionRequestUdpPacket packet) throws IOException {
        BitCamSession session = this.coordinator.session(packet.sessionId());
        if (session == null || !this.coordinator.validateSecret(session, packet.secret())) {
            this.platform.logger().warn("Rejected BitCam UDP session request from " + address + " for unknown or invalid session " + packet.sessionId());
            return;
        }

        session.accept(address, packet.sendEnabled(), packet.receiveEnabled());
        this.send(address, BitCamUdpCodec.encode(new SessionAcceptedUdpPacket(packet.sessionId())));
        this.platform.logger().info(
            "Accepted BitCam UDP session "
                + packet.sessionId()
                + " for player "
                + session.playerId()
                + " from "
                + address
                + " [send="
                + packet.sendEnabled()
                + ", recv="
                + packet.receiveEnabled()
                + "]"
        );
    }

    private void handleKeepAlive(SocketAddress address, KeepAliveUdpPacket packet) {
        BitCamSession session = this.coordinator.session(packet.sessionId());
        if (session != null && address.equals(session.address())) {
            session.touch();
        }
    }

    private void handleVideoFrame(SocketAddress address, VideoFrameUdpPacket packet, byte[] encodedBytes) throws IOException {
        BitCamSession session = this.coordinator.sessionByPlayer(packet.streamerId());
        if (session == null || session.address() == null || !session.address().equals(address) || !session.sendEnabled()) {
            return;
        }

        session.touch();
        // A frame is split across many UDP fragments that arrive back-to-back. Resolving nearby
        // viewers (an O(players) world scan) and recording stats once per frame — reusing the result
        // for every remaining fragment — avoids redoing that work tens of times per frame.
        this.forwardToViewers(this.viewersForFrame(packet), encodedBytes);
    }

    private Collection<UUID> viewersForFrame(VideoFrameUdpPacket packet) {
        CachedFrameViewers cached = this.viewerCacheByStreamer.get(packet.streamerId());
        if (cached != null && cached.frameId() == packet.frameId()) {
            return cached.viewers();
        }

        Collection<UUID> viewers = this.coordinator.viewerResolver().resolveViewers(packet.streamerId(), this.config.radius());
        this.viewerCacheByStreamer.put(packet.streamerId(), new CachedFrameViewers(packet.frameId(), viewers));
        this.coordinator.recordVideoFrame(packet, viewers.size());
        return viewers;
    }

    private void handleVideoFec(SocketAddress address, VideoFecUdpPacket packet, byte[] encodedBytes) throws IOException {
        BitCamSession session = this.coordinator.sessionByPlayer(packet.streamerId());
        if (session == null || session.address() == null || !session.address().equals(address) || !session.sendEnabled()) {
            return;
        }

        session.touch();
        // FEC parity arrives right after a frame's data fragments, so the cached viewer set for this
        // (streamer, frameId) is almost always warm — reuse it. We never record stats for parity.
        this.forwardToViewers(this.viewersForFec(packet.streamerId(), packet.frameId()), encodedBytes);
    }

    private Collection<UUID> viewersForFec(UUID streamerId, int frameId) {
        CachedFrameViewers cached = this.viewerCacheByStreamer.get(streamerId);
        if (cached != null && cached.frameId() == frameId) {
            return cached.viewers();
        }
        // Parity for a frame whose data fragments we never saw (or whose cache rolled over): resolve
        // viewers without caching/recording so we don't disturb data-frame stats.
        return this.coordinator.viewerResolver().resolveViewers(streamerId, this.config.radius());
    }

    private void handleStreamStopped(SocketAddress address, StreamStoppedUdpPacket packet, byte[] encodedBytes) throws IOException {
        BitCamSession session = this.coordinator.sessionByPlayer(packet.streamerId());
        if (session == null || session.address() == null || !session.address().equals(address) || !session.sendEnabled()) {
            return;
        }

        session.touch();
        this.coordinator.clearStreamState(packet.streamerId());
        Collection<UUID> viewers = this.coordinator.viewerResolver().resolveViewers(packet.streamerId(), this.config.radius());
        this.forwardToViewers(viewers, encodedBytes);
    }

    private void forwardToViewers(Collection<UUID> viewers, byte[] encodedBytes) throws IOException {
        for (UUID viewerId : viewers) {
            BitCamSession viewerSession = this.coordinator.sessionByPlayer(viewerId);
            if (viewerSession == null || viewerSession.address() == null || !viewerSession.receiveEnabled()) {
                continue;
            }

            this.send(viewerSession.address(), encodedBytes);
        }
    }

    private void send(SocketAddress address, byte[] encodedBytes) throws IOException {
        this.channel.send(ByteBuffer.wrap(encodedBytes), address);
    }

    private void maybeCleanupSessions() {
        long now = System.nanoTime();
        if (now - this.lastSessionCleanupNanos < SESSION_CLEANUP_INTERVAL_NANOS) {
            return;
        }
        this.lastSessionCleanupNanos = now;
        this.coordinator.cleanupExpiredSessions();
        // Drop cached viewer sets for streamers that no longer have a live session.
        this.viewerCacheByStreamer.keySet().removeIf(streamerId -> this.coordinator.sessionByPlayer(streamerId) == null);
    }

    private record CachedFrameViewers(int frameId, Collection<UUID> viewers) {
    }

    @Override
    public void close() {
        try {
            if (this.channel != null) {
                this.channel.close();
            }
        } catch (IOException exception) {
            this.platform.logger().error("Failed to close BitCam UDP server", exception);
        } finally {
            this.executor.shutdownNow();
        }
    }
}
