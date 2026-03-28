package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.common.PlatformAccess;
import dev.nexbit.bitcam.protocol.BitCamProtocol;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import dev.nexbit.bitcam.protocol.udp.BitCamUdpCodec;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import dev.nexbit.bitcam.protocol.udp.BitCamUdpPacket;
import dev.nexbit.bitcam.protocol.udp.KeepAliveUdpPacket;
import dev.nexbit.bitcam.protocol.udp.SessionAcceptedUdpPacket;
import dev.nexbit.bitcam.protocol.udp.SessionRequestUdpPacket;
import dev.nexbit.bitcam.protocol.udp.StreamStoppedUdpPacket;
import dev.nexbit.bitcam.protocol.udp.VideoFrameUdpPacket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class BitCamUdpClient implements AutoCloseable {
    private final PlatformAccess platform;
    private final RemoteFrameStore frameStore;
    private final ExecutorService receiverExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-udp-client");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-udp-keepalive");
        thread.setDaemon(true);
        return thread;
    });

    private DatagramChannel channel;
    private ServerWelcomeSignalPacket welcome;
    private volatile boolean connected;
    private ScheduledFuture<?> handshakeTask;
    private ScheduledFuture<?> keepAliveTask;

    public BitCamUdpClient(PlatformAccess platform, RemoteFrameStore frameStore) {
        this.platform = platform;
        this.frameStore = frameStore;
    }

    public void connect(ServerWelcomeSignalPacket welcome) {
        this.welcome = welcome;
        this.connected = false;

        try {
            this.channel = DatagramChannel.open();
            this.channel.connect(new InetSocketAddress(welcome.udpHost(), welcome.udpPort()));
            this.receiverExecutor.submit(this::listen);
            this.handshakeTask = this.keepAliveExecutor.scheduleAtFixedRate(this::sendSessionRequest, 0L, 1L, TimeUnit.SECONDS);
            this.keepAliveTask = this.keepAliveExecutor.scheduleAtFixedRate(this::sendKeepAlive, 5L, 5L, TimeUnit.SECONDS);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to connect BitCam UDP client", exception);
        }
    }

    public boolean isConnected() {
        return this.connected;
    }

    public RemoteFrameStore frameStore() {
        return this.frameStore;
    }

    public void sendFrame(UUID playerId, EncodedLocalFrame frame, BitCamBubbleStyle bubbleStyle) {
        if (!this.connected || this.welcome == null) {
            return;
        }

        int fragmentPayloadSize = Math.max(512, this.welcome.mtu() - 128);
        int fragmentCount = Math.max(1, (int) Math.ceil((double) frame.payload().length / fragmentPayloadSize));

        for (int fragmentIndex = 0; fragmentIndex < fragmentCount; fragmentIndex++) {
            int start = fragmentIndex * fragmentPayloadSize;
            int end = Math.min(frame.payload().length, start + fragmentPayloadSize);
            byte[] fragment = new byte[end - start];
            System.arraycopy(frame.payload(), start, fragment, 0, fragment.length);

            this.sendQuietly(BitCamUdpCodec.encode(new VideoFrameUdpPacket(
                playerId,
                frame.frameId(),
                fragmentIndex,
                fragmentCount,
                frame.width(),
                frame.height(),
                frame.captureTimeMillis(),
                bubbleStyle,
                frame.keyFrame(),
                fragment
            )));
        }
    }

    public void sendStop(UUID playerId) {
        if (!this.connected) {
            return;
        }

        this.sendQuietly(BitCamUdpCodec.encode(new StreamStoppedUdpPacket(playerId)));
    }

    private void listen() {
        ByteBuffer buffer = ByteBuffer.allocate(this.welcome.mtu() * 2);
        while (this.channel != null && this.channel.isOpen()) {
            try {
                buffer.clear();
                if (this.channel.receive(buffer) == null) {
                    continue;
                }

                byte[] bytes = new byte[buffer.position()];
                buffer.flip();
                buffer.get(bytes);
                this.handle(BitCamUdpCodec.decode(bytes));
            } catch (IOException exception) {
                if (this.channel != null && this.channel.isOpen()) {
                    this.platform.logger().error("BitCam UDP client failed while receiving packets", exception);
                }
            } catch (RuntimeException exception) {
                this.platform.logger().warn("BitCam client dropped malformed UDP packet: " + exception.getMessage());
            }
        }
    }

    private void handle(BitCamUdpPacket packet) {
        if (packet instanceof SessionAcceptedUdpPacket accepted) {
            if (accepted.sessionId().equals(this.welcome.sessionId())) {
                this.connected = true;
                if (this.handshakeTask != null) {
                    this.handshakeTask.cancel(false);
                    this.handshakeTask = null;
                }
                this.platform.logger().info("BitCam UDP session accepted for " + this.welcome.sessionId());
            }
        } else if (packet instanceof VideoFrameUdpPacket frame) {
            this.frameStore.accept(frame);
        } else if (packet instanceof StreamStoppedUdpPacket stopped) {
            this.frameStore.accept(stopped);
        }
    }

    private void sendSessionRequest() {
        if (this.connected || this.welcome == null || this.channel == null || !this.channel.isOpen()) {
            return;
        }

        this.sendQuietly(BitCamUdpCodec.encode(new SessionRequestUdpPacket(
            this.welcome.protocolVersion(),
            this.welcome.sessionId(),
            this.welcome.secret(),
            true,
            true
        )));
    }

    private void sendKeepAlive() {
        if (!this.connected || this.welcome == null) {
            return;
        }

        this.sendQuietly(BitCamUdpCodec.encode(new KeepAliveUdpPacket(this.welcome.sessionId())));
    }

    private void sendQuietly(byte[] bytes) {
        try {
            this.send(bytes);
        } catch (IOException exception) {
            this.platform.logger().error("Failed to send BitCam UDP packet", exception);
        }
    }

    private void send(byte[] bytes) throws IOException {
        this.channel.write(ByteBuffer.wrap(bytes));
    }

    @Override
    public void close() {
        try {
            if (this.channel != null) {
                this.channel.close();
            }
        } catch (IOException exception) {
            this.platform.logger().error("Failed to close BitCam UDP client", exception);
        } finally {
            if (this.handshakeTask != null) {
                this.handshakeTask.cancel(false);
                this.handshakeTask = null;
            }
            if (this.keepAliveTask != null) {
                this.keepAliveTask.cancel(false);
                this.keepAliveTask = null;
            }
            this.keepAliveExecutor.shutdownNow();
            this.receiverExecutor.shutdownNow();
        }
    }
}
