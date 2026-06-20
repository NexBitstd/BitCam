package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.common.PlatformAccess;
import dev.nexbit.bitcam.protocol.BitCamProtocol;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import dev.nexbit.bitcam.protocol.udp.BitCamUdpCodec;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
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
import java.net.PortUnreachableException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

public final class BitCamUdpClient implements AutoCloseable {
    // Data fragments per FEC group: one parity packet protects up to this many shards, recovering any
    // single lost shard among them. 4 keeps overhead modest (~1 extra packet per 4) while covering the
    // typical small talking-head frame in a single group.
    private static final int FEC_GROUP_SIZE = 4;

    private final PlatformAccess platform;
    private final RemoteFrameStore frameStore;
    private final Runnable keyframeRequestHandler;
    private final IntConsumer receiverReportHandler;
    private final boolean fecEnabled;
    private final ExecutorService receiverExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-udp-client");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService senderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-udp-sender");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-udp-keepalive");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<FrameSendTask> pendingFrameTask = new AtomicReference<>();
    private final Semaphore frameSendSignal = new Semaphore(0);

    private static final long UNREACHABLE_LOG_INTERVAL_NANOS = 15_000_000_000L;
    private static final long SEND_FAILURE_LOG_INTERVAL_NANOS = 15_000_000_000L;

    // Read by the receive/send/keep-alive threads; swapped wholesale by reopenChannel() on a network
    // change, so it must be volatile.
    private volatile DatagramChannel channel;
    private ServerWelcomeSignalPacket welcome;
    private volatile boolean connected;
    private volatile boolean closing;
    private volatile ScheduledFuture<?> handshakeTask;
    private ScheduledFuture<?> keepAliveTask;
    // Guards against queueing a socket rebuild for every failed packet during a single outage.
    private final AtomicBoolean reconnectInFlight = new AtomicBoolean(false);
    private long lastUnreachableLogNanos;
    private long lastSendFailureLogNanos;

    public BitCamUdpClient(
        PlatformAccess platform,
        RemoteFrameStore frameStore,
        Runnable keyframeRequestHandler,
        IntConsumer receiverReportHandler,
        boolean fecEnabled
    ) {
        this.platform = platform;
        this.frameStore = frameStore;
        this.keyframeRequestHandler = keyframeRequestHandler;
        this.receiverReportHandler = receiverReportHandler;
        this.fecEnabled = fecEnabled;
        // Let the frame store ask our streamer for a keyframe (mid-stream join / loss recovery) and
        // report its reception loss back upstream so our streamer can adapt its bitrate.
        this.frameStore.setKeyframeRequestSink(this::sendKeyframeRequest);
        this.frameStore.setReceiverReportSink(this::sendReceiverReport);
    }

    public void connect(ServerWelcomeSignalPacket welcome) {
        this.welcome = welcome;
        this.connected = false;

        try {
            this.channel = DatagramChannel.open();
            this.channel.connect(new InetSocketAddress(welcome.udpHost(), welcome.udpPort()));
            this.receiverExecutor.submit(this::listen);
            this.senderExecutor.submit(this::runSender);
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
        this.pendingFrameTask.set(new FrameSendTask(playerId, frame, bubbleStyle));
        this.frameSendSignal.release();
    }

    private void runSender() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                this.frameSendSignal.acquire();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
            this.frameSendSignal.drainPermits();

            FrameSendTask task = this.pendingFrameTask.getAndSet(null);
            if (task == null || !this.connected || this.welcome == null) {
                continue;
            }
            this.doSendFrame(task.playerId(), task.frame(), task.bubbleStyle());
        }
    }

    private void doSendFrame(UUID playerId, EncodedLocalFrame frame, BitCamBubbleStyle bubbleStyle) {
        int fragmentPayloadSize = Math.max(512, this.welcome.mtu() - 128);
        byte[] payload = frame.payload();
        int fragmentCount = Math.max(1, (int) Math.ceil((double) payload.length / fragmentPayloadSize));
        long pacingNanos = this.fragmentPacingNanos(fragmentCount);

        for (int fragmentIndex = 0; fragmentIndex < fragmentCount; fragmentIndex++) {
            int start = fragmentIndex * fragmentPayloadSize;
            int end = Math.min(payload.length, start + fragmentPayloadSize);
            byte[] fragment = new byte[end - start];
            System.arraycopy(payload, start, fragment, 0, fragment.length);

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
                frame.codec(),
                fragment
            )));

            // Spread fragments across the frame interval instead of firing the whole burst at once:
            // a back-to-back burst overflows router/socket queues and drops packets, and losing a
            // single fragment discards the entire frame on the receiver.
            if (pacingNanos > 0L && fragmentIndex < fragmentCount - 1) {
                LockSupport.parkNanos(pacingNanos);
            }
        }

        // FEC parity lets the receiver rebuild a single lost fragment per group without us resending.
        if (this.fecEnabled && fragmentCount >= 2) {
            this.sendFecGroups(playerId, frame.frameId(), payload, fragmentCount, fragmentPayloadSize, pacingNanos);
        }
    }

    private void sendFecGroups(UUID playerId, int frameId, byte[] payload, int fragmentCount, int shardLength, long pacingNanos) {
        for (int groupStart = 0; groupStart < fragmentCount; groupStart += FEC_GROUP_SIZE) {
            int groupShardCount = Math.min(FEC_GROUP_SIZE, fragmentCount - groupStart);
            byte[] parity = new byte[shardLength];
            for (int shard = 0; shard < groupShardCount; shard++) {
                int start = (groupStart + shard) * shardLength;
                int end = Math.min(payload.length, start + shardLength);
                // XOR over the shard's real bytes only; missing tail bytes (short final shard) are an
                // implicit zero pad, so they contribute nothing — exactly what reconstruction expects.
                for (int i = start; i < end; i++) {
                    parity[i - start] ^= payload[i];
                }
            }

            this.sendQuietly(BitCamUdpCodec.encode(new VideoFecUdpPacket(
                playerId, frameId, groupStart, groupShardCount, shardLength, payload.length, parity
            )));

            if (pacingNanos > 0L) {
                LockSupport.parkNanos(pacingNanos);
            }
        }
    }

    private long fragmentPacingNanos(int fragmentCount) {
        if (fragmentCount <= 1) {
            return 0L;
        }
        int fps = Math.max(1, this.welcome.fps());
        long frameIntervalNanos = 1_000_000_000L / fps;
        // Pace over ~60% of the frame interval, leaving headroom so the next frame isn't held up.
        return (long) (frameIntervalNanos * 0.6D) / (fragmentCount - 1);
    }

    public void sendStop(UUID playerId) {
        if (!this.connected) {
            return;
        }

        this.sendQuietly(BitCamUdpCodec.encode(new StreamStoppedUdpPacket(playerId)));
    }

    private void listen() {
        ByteBuffer buffer = ByteBuffer.allocate(this.welcome.mtu() * 2);
        while (!this.closing) {
            DatagramChannel active = this.channel;
            if (active == null || !active.isOpen()) {
                // Channel is mid-rebuild after a network change — wait briefly and re-read it.
                LockSupport.parkNanos(50_000_000L);
                continue;
            }
            try {
                buffer.clear();
                if (active.receive(buffer) == null) {
                    continue;
                }

                byte[] bytes = new byte[buffer.position()];
                buffer.flip();
                buffer.get(bytes);
                this.handle(BitCamUdpCodec.decode(bytes));
            } catch (PortUnreachableException exception) {
                // The OS got an ICMP "port unreachable" for a packet we sent: the server's UDP port
                // isn't accepting datagrams. The channel stays usable, so keep retrying the handshake
                // and just warn (throttled) instead of spamming a stack trace every second.
                this.noteServerUnreachable();
            } catch (IOException exception) {
                // A reconnect closes the old channel out from under receive(); only surface errors that
                // aren't the result of that swap or of shutdown.
                if (!this.closing && active == this.channel && active.isOpen()) {
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
        } else if (packet instanceof VideoFecUdpPacket fec) {
            this.frameStore.accept(fec);
        } else if (packet instanceof StreamStoppedUdpPacket stopped) {
            this.frameStore.accept(stopped);
        } else if (packet instanceof KeyframeRequestUdpPacket && this.keyframeRequestHandler != null) {
            // A viewer asked our stream for a fresh keyframe — force one on our encoder.
            this.keyframeRequestHandler.run();
        } else if (packet instanceof ReceiverReportUdpPacket report && this.receiverReportHandler != null) {
            // A viewer reported its reception loss — feed it to our congestion controller.
            this.receiverReportHandler.accept(report.lossPermille());
        }
    }

    private void sendKeyframeRequest(UUID streamerId) {
        if (!this.connected || this.welcome == null) {
            return;
        }
        this.sendQuietly(BitCamUdpCodec.encode(new KeyframeRequestUdpPacket(this.welcome.sessionId(), streamerId)));
    }

    private void sendReceiverReport(UUID streamerId, int lossPermille) {
        if (!this.connected || this.welcome == null) {
            return;
        }
        this.sendQuietly(BitCamUdpCodec.encode(new ReceiverReportUdpPacket(this.welcome.sessionId(), streamerId, lossPermille)));
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
        } catch (PortUnreachableException exception) {
            this.noteServerUnreachable();
        } catch (IOException exception) {
            // "Can't assign requested address" and friends mean the socket's bound local address went
            // away (Wi-Fi roam / VPN / interface switch). Throttle the log and rebuild the socket on a
            // currently-valid local address instead of spamming a stack trace every keep-alive.
            this.noteSendFailure(exception);
            this.scheduleReconnect();
        }
    }

    private void noteSendFailure(IOException exception) {
        long now = System.nanoTime();
        if ((now - this.lastSendFailureLogNanos) < SEND_FAILURE_LOG_INTERVAL_NANOS) {
            return;
        }
        this.lastSendFailureLogNanos = now;
        this.platform.logger().warn(
            "BitCam UDP send failed (" + exception.getMessage() + "); rebuilding the socket. "
                + "Expected after a network change (Wi-Fi roam / VPN / interface switch)."
        );
    }

    // Rebuild the datagram socket on the keep-alive thread. A network change can strand the socket on a
    // local address that no longer exists; a fresh socket re-binds to a currently-valid one.
    private void scheduleReconnect() {
        if (this.closing || this.welcome == null) {
            return;
        }
        // Collapse the burst of failures during one outage into a single rebuild.
        if (!this.reconnectInFlight.compareAndSet(false, true)) {
            return;
        }
        this.keepAliveExecutor.submit(this::reopenChannel);
    }

    private void reopenChannel() {
        try {
            if (this.closing || this.welcome == null) {
                return;
            }
            DatagramChannel fresh = DatagramChannel.open();
            fresh.connect(new InetSocketAddress(this.welcome.udpHost(), this.welcome.udpPort()));
            DatagramChannel previous = this.channel;
            this.channel = fresh;
            // The fresh socket must redo the UDP session handshake from scratch.
            this.connected = false;
            if (previous != null) {
                try {
                    previous.close();
                } catch (IOException ignored) {
                    // Best-effort: the old socket is already unusable.
                }
            }
            // After the first accept the handshake task is cancelled; a rebuild needs it running again.
            if (this.handshakeTask == null && !this.closing) {
                this.handshakeTask = this.keepAliveExecutor.scheduleAtFixedRate(
                    this::sendSessionRequest, 0L, 1L, TimeUnit.SECONDS
                );
            }
        } catch (IOException exception) {
            // The interface may still be down; the next failed send schedules another attempt.
        } finally {
            this.reconnectInFlight.set(false);
        }
    }

    private void noteServerUnreachable() {
        long now = System.nanoTime();
        if ((now - this.lastUnreachableLogNanos) < UNREACHABLE_LOG_INTERVAL_NANOS) {
            return;
        }
        this.lastUnreachableLogNanos = now;
        String endpoint = this.welcome == null ? "the server" : this.welcome.udpHost() + ":" + this.welcome.udpPort();
        this.platform.logger().warn(
            "BitCam cannot reach the UDP media port at " + endpoint + " (ICMP port-unreachable). "
                + "Check that the server's udp.host is an address reachable from clients, udp.port is open, "
                + "and the server host's firewall allows inbound UDP on that port."
        );
    }

    private void send(byte[] bytes) throws IOException {
        DatagramChannel active = this.channel;
        if (active == null) {
            throw new ClosedChannelException();
        }
        active.write(ByteBuffer.wrap(bytes));
    }

    @Override
    public void close() {
        this.closing = true;
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
            this.senderExecutor.shutdownNow();
            this.receiverExecutor.shutdownNow();
        }
    }

    private record FrameSendTask(UUID playerId, EncodedLocalFrame frame, BitCamBubbleStyle bubbleStyle) {}
}
