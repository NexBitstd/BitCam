package dev.nexbit.bitcam.servercommon;

import dev.nexbit.bitcam.common.PlatformAccess;
import dev.nexbit.bitcam.protocol.udp.BitCamUdpCodec;
import dev.nexbit.bitcam.protocol.udp.BitCamUdpPacket;
import dev.nexbit.bitcam.protocol.udp.KeepAliveUdpPacket;
import dev.nexbit.bitcam.protocol.udp.SessionAcceptedUdpPacket;
import dev.nexbit.bitcam.protocol.udp.SessionRequestUdpPacket;
import dev.nexbit.bitcam.protocol.udp.StreamStoppedUdpPacket;
import dev.nexbit.bitcam.protocol.udp.VideoFrameUdpPacket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BitCamUdpServer implements AutoCloseable {
    private final PlatformAccess platform;
    private final BitCamServerConfig config;
    private final BitCamServerCoordinator coordinator;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-udp-server");
        thread.setDaemon(true);
        return thread;
    });

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
                this.coordinator.cleanupExpiredSessions();
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
        } else if (packet instanceof StreamStoppedUdpPacket stopped) {
            this.handleStreamStopped(address, stopped, encodedBytes);
        }
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
        Collection<UUID> viewers = this.coordinator.viewerResolver().resolveViewers(packet.streamerId(), this.config.radius());
        this.coordinator.recordVideoFrame(packet, viewers.size());
        this.forwardToViewers(viewers, encodedBytes);
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
