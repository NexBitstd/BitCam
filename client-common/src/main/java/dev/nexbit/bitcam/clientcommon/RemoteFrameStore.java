package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.common.PlatformLogger;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import dev.nexbit.bitcam.protocol.udp.BitCamVideoCodec;
import dev.nexbit.bitcam.protocol.udp.StreamStoppedUdpPacket;
import dev.nexbit.bitcam.protocol.udp.VideoFecUdpPacket;
import dev.nexbit.bitcam.protocol.udp.VideoFrameUdpPacket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/**
 * Receives video fragments, reassembles complete frames and hands them to a background decode thread
 * so the render thread never has to decode. Decoded frames land in a per-stream jitter buffer
 * ({@link RemoteStreamPlayout}) that smooths out bursty network delivery.
 */
public final class RemoteFrameStore implements AutoCloseable {
    private static final long DECODER_FAILURE_LOG_INTERVAL_NANOS = 5_000_000_000L;
    // After this many decoder restarts for one stream we stop rebuilding it: a persistent failure
    // (almost always missing/unloadable native FFmpeg) won't fix itself, and retrying every keyframe
    // just spawns short-lived grab threads forever.
    private static final int MAX_DECODER_FAILURES = 5;

    private final Map<UUID, RemoteStream> streams = new ConcurrentHashMap<>();
    private final Map<String, PendingFrame> pendingFrames = new ConcurrentHashMap<>();
    // Streamers we've already logged a "started receiving" line for, so the diagnostic fires once per
    // stream rather than on every fragment.
    private final Set<UUID> receivingLogged = ConcurrentHashMap.newKeySet();
    private final PlatformLogger logger;
    // Single decode thread with a small bounded queue: if decoding can't keep up, the oldest queued
    // frame is dropped rather than letting the backlog (and memory) grow without bound — for video,
    // skipping a stale frame is always better than falling further behind.
    private final ThreadPoolExecutor decodeExecutor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4),
        runnable -> {
            Thread thread = new Thread(runnable, "bitcam-decode");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    // Throttle for how often a stream may ask its streamer for a fresh keyframe.
    private static final long KEYFRAME_REQUEST_INTERVAL_NANOS = 1_000_000_000L;
    // How often a stream reports its reception loss back to the streamer for bitrate adaptation.
    private static final long RECEIVER_REPORT_INTERVAL_NANOS = 1_000_000_000L;
    // Sink that sends a keyframe request for a given streamer; wired to the UDP client on connect.
    private volatile Consumer<UUID> keyframeRequestSink = streamerId -> {
    };
    // Sink that reports loss (per-mille) for a given streamer; wired to the UDP client on connect.
    private volatile ObjIntConsumer<UUID> receiverReportSink = (streamerId, lossPermille) -> {
    };
    private volatile long lastDecoderFailureLogNanos;

    public RemoteFrameStore() {
        this(null);
    }

    public RemoteFrameStore(PlatformLogger logger) {
        this.logger = logger;
    }

    public void setKeyframeRequestSink(Consumer<UUID> sink) {
        this.keyframeRequestSink = sink == null ? streamerId -> {
        } : sink;
    }

    public void setReceiverReportSink(ObjIntConsumer<UUID> sink) {
        this.receiverReportSink = sink == null ? (streamerId, lossPermille) -> {
        } : sink;
    }

    public void accept(VideoFrameUdpPacket packet) {
        if (this.logger != null && this.receivingLogged.add(packet.streamerId())) {
            // One-shot diagnostic: confirms fragments are arriving from this streamer. If this never
            // appears, the problem is upstream (routing / UDP), not the local decoder.
            this.logger.info("BitCam started receiving " + packet.codec() + " video from " + packet.streamerId());
        }
        String key = packet.streamerId() + ":" + packet.frameId();
        PendingFrame pending = this.pendingFrames.computeIfAbsent(key, ignored -> new PendingFrame());
        pending.recordDataMeta(packet);
        pending.fragments.put(packet.fragmentIndex(), packet.payload());
        this.tryComplete(key, pending);
    }

    public void accept(VideoFecUdpPacket packet) {
        String key = packet.streamerId() + ":" + packet.frameId();
        PendingFrame pending = this.pendingFrames.computeIfAbsent(key, ignored -> new PendingFrame());
        pending.parities.put(
            packet.groupStart(),
            new FecGroup(packet.groupStart(), packet.groupShardCount(), packet.shardLength(), packet.totalPayloadLength(), packet.parity())
        );
        this.tryComplete(key, pending);
    }

    // accept(...) only ever runs on the single UDP receive thread, so this read-modify-write over a
    // pending frame needs no extra locking.
    private void tryComplete(String key, PendingFrame pending) {
        if (pending.fragmentCount < 0) {
            // No data fragment seen yet: we don't know the frame shape, so we can neither assemble nor
            // reconstruct. Any parity already buffered will be used once the first data fragment lands.
            return;
        }
        if (!pending.isComplete()) {
            pending.reconstructFromParity();
        }
        byte[] assembled = pending.assemble();
        if (assembled == null) {
            return;
        }

        this.pendingFrames.remove(key);
        RemoteFrame encoded = new RemoteFrame(
            pending.streamerId,
            pending.frameId,
            pending.width,
            pending.height,
            pending.captureTimeMillis,
            pending.bubbleStyle,
            pending.codec,
            pending.keyFrame,
            assembled,
            System.currentTimeMillis()
        );
        this.submitDecode(encoded);
    }

    public void accept(StreamStoppedUdpPacket packet) {
        this.receivingLogged.remove(packet.streamerId());
        RemoteStream removed = this.streams.remove(packet.streamerId());
        if (removed != null) {
            removed.close();
        }
    }

    public DecodedFrame frame(UUID streamerId) {
        RemoteStream stream = this.streams.get(streamerId);
        return stream == null ? null : stream.playout.current(System.nanoTime());
    }

    public Map<UUID, DecodedFrame> snapshot() {
        Map<UUID, DecodedFrame> result = new HashMap<>();
        this.streams.forEach((streamerId, stream) -> {
            DecodedFrame frame = stream.playout.peekCurrent();
            if (frame != null) {
                result.put(streamerId, frame);
            }
        });
        return result;
    }

    public void pruneExpired(long maxAgeMillis) {
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        this.streams.values().removeIf(stream -> {
            if (stream.playout.lastOfferMillis() >= cutoff) {
                return false;
            }
            stream.close();
            this.receivingLogged.remove(stream.streamerId);
            return true;
        });
        this.pendingFrames.entrySet().removeIf(entry -> entry.getValue().createdAtMillis < cutoff);
    }

    @Override
    public void close() {
        this.decodeExecutor.shutdownNow();
        for (RemoteStream stream : this.streams.values()) {
            try {
                stream.close();
            } catch (RuntimeException ignored) {
                // A stale decoder must not crash the client while joining or leaving a server.
            }
        }
        this.streams.clear();
        this.pendingFrames.clear();
        this.receivingLogged.clear();
    }

    private void submitDecode(RemoteFrame encoded) {
        RemoteStream stream = this.streams.computeIfAbsent(encoded.streamerId(), RemoteStream::new);
        // decode() runs on the single decode thread; the decoder delivers finished frames to the
        // playout — synchronously for JPEG, or later from its own thread for H.264.
        this.decodeExecutor.submit(() -> stream.decode(encoded));
    }

    private void noteDecoderFailure(UUID streamerId, Throwable exception) {
        if (this.logger == null) {
            return;
        }
        long now = System.nanoTime();
        if ((now - this.lastDecoderFailureLogNanos) < DECODER_FAILURE_LOG_INTERVAL_NANOS) {
            return;
        }
        this.lastDecoderFailureLogNanos = now;
        this.logger.error(
            "BitCam failed to decode remote H.264 stream from " + streamerId
                + ". Fix FFmpeg/JavaCV native loading on this client.",
            exception
        );
    }

    // One-shot decoder lifecycle line per stream restart. With the stream torn down every ~10s when no
    // frame decodes, the cadence itself is the signal: "received first keyframe" without a following
    // "decoded first frame" points at the grabber; no keyframe line at all points at delivery upstream.
    private void noteDecoderDiagnostic(UUID streamerId, String message) {
        if (this.logger != null) {
            this.logger.info("BitCam H.264 decoder for " + streamerId + ": " + message);
        }
    }

    private void noteDecoderGivenUp(UUID streamerId) {
        if (this.logger == null) {
            return;
        }
        this.logger.error(
            "BitCam gave up decoding the H.264 stream from " + streamerId + " after " + MAX_DECODER_FAILURES
                + " failed attempts. Native FFmpeg/JavaCV libraries are likely missing or failed to load on this client."
        );
    }

    /** Per-stream decoder + playout pair, so a stream's codec state and buffer are removed together. */
    private final class RemoteStream implements AutoCloseable {
        private final UUID streamerId;
        private final RemoteStreamPlayout playout = new RemoteStreamPlayout();
        private FrameDecoder decoder;
        private BitCamVideoCodec codec;
        private int lastFrameId = -1;
        private boolean awaitingKeyframe = true;
        private long lastKeyframeRequestNanos;
        private int decoderFailures;
        private boolean decoderGivenUp;
        private int windowReceived;
        private int windowLost;
        private long lastReportNanos;

        private RemoteStream(UUID streamerId) {
            this.streamerId = streamerId;
        }

        void decode(RemoteFrame frame) {
            this.accountReception(frame);
            if (frame.codec() == BitCamVideoCodec.H264 && !this.decoderGivenUp) {
                this.trackKeyframeNeed(frame);
            }
            FrameDecoder activeDecoder = this.decoderFor(frame.codec());
            if (activeDecoder != null) {
                activeDecoder.decode(frame, this.playout::offer);
            }
            this.lastFrameId = frame.frameId();
        }

        // Track how much of the stream arrives and periodically report the loss fraction to the
        // streamer so it can adapt its bitrate to the worst link among its viewers.
        private void accountReception(RemoteFrame frame) {
            if (this.lastFrameId >= 0 && frame.frameId() > (this.lastFrameId + 1)) {
                this.windowLost += frame.frameId() - this.lastFrameId - 1;
            }
            this.windowReceived++;
            long now = System.nanoTime();
            if (this.lastReportNanos == 0L) {
                this.lastReportNanos = now;
                return;
            }
            if ((now - this.lastReportNanos) < RECEIVER_REPORT_INTERVAL_NANOS) {
                return;
            }
            this.lastReportNanos = now;
            int total = this.windowReceived + this.windowLost;
            int lossPermille = total <= 0 ? 0 : (int) Math.round(1000.0D * this.windowLost / total);
            this.windowReceived = 0;
            this.windowLost = 0;
            RemoteFrameStore.this.receiverReportSink.accept(this.streamerId, lossPermille);
        }

        // Ask the streamer for a keyframe when we have nothing decodable yet (joined mid-stream) or a
        // frame went missing — loss corrupts an inter-frame stream until the next IDR arrives.
        private void trackKeyframeNeed(RemoteFrame frame) {
            if (frame.keyFrame()) {
                this.awaitingKeyframe = false;
                return;
            }
            boolean gap = this.lastFrameId >= 0 && frame.frameId() > (this.lastFrameId + 1);
            if (this.awaitingKeyframe || gap) {
                this.awaitingKeyframe = true;
                this.maybeRequestKeyframe();
            }
        }

        private void maybeRequestKeyframe() {
            long now = System.nanoTime();
            if ((now - this.lastKeyframeRequestNanos) < KEYFRAME_REQUEST_INTERVAL_NANOS) {
                return;
            }
            this.lastKeyframeRequestNanos = now;
            RemoteFrameStore.this.keyframeRequestSink.accept(this.streamerId);
        }

        // Pick (and lazily create) the decoder for the stream's codec, rebuilding it if the codec
        // ever changes — keeps the inter-frame decoder's reference state tied to one codec.
        private FrameDecoder decoderFor(BitCamVideoCodec frameCodec) {
            if (this.decoder instanceof H264FrameDecoder h264Decoder && h264Decoder.failed()) {
                this.closeDecoder();
                this.awaitingKeyframe = true;
                if (++this.decoderFailures >= MAX_DECODER_FAILURES) {
                    if (!this.decoderGivenUp) {
                        this.decoderGivenUp = true;
                        RemoteFrameStore.this.noteDecoderGivenUp(this.streamerId);
                    }
                    return null;
                }
                this.maybeRequestKeyframe();
            }
            if (this.decoderGivenUp) {
                return null;
            }
            if (this.decoder == null || this.codec != frameCodec) {
                this.closeDecoder();
                this.decoder = frameCodec == BitCamVideoCodec.H264
                    ? new H264FrameDecoder(
                        exception -> RemoteFrameStore.this.noteDecoderFailure(this.streamerId, exception),
                        message -> RemoteFrameStore.this.noteDecoderDiagnostic(this.streamerId, message))
                    : new JpegFrameDecoder();
                this.codec = frameCodec;
            }
            return this.decoder;
        }

        private void closeDecoder() {
            if (this.decoder != null) {
                try {
                    this.decoder.close();
                } catch (Exception ignored) {
                    // Decoders are best-effort to close.
                }
                this.decoder = null;
            }
        }

        @Override
        public void close() {
            this.closeDecoder();
        }
    }

    /**
     * Accumulates a single frame's data shards plus any FEC parity, reconstructing one missing shard
     * per parity group when possible. Touched only on the UDP receive thread, so plain maps are fine.
     */
    private static final class PendingFrame {
        private final long createdAtMillis = System.currentTimeMillis();
        private final Map<Integer, byte[]> fragments = new HashMap<>();
        private final Map<Integer, FecGroup> parities = new HashMap<>();

        // Frame shape/metadata, captured from the first data fragment; -1 until any data arrives.
        private int fragmentCount = -1;
        private UUID streamerId;
        private int frameId;
        private int width;
        private int height;
        private long captureTimeMillis;
        private BitCamBubbleStyle bubbleStyle;
        private BitCamVideoCodec codec;
        private boolean keyFrame;

        private void recordDataMeta(VideoFrameUdpPacket packet) {
            this.fragmentCount = packet.fragmentCount();
            this.streamerId = packet.streamerId();
            this.frameId = packet.frameId();
            this.width = packet.width();
            this.height = packet.height();
            this.captureTimeMillis = packet.captureTimeMillis();
            this.bubbleStyle = packet.bubbleStyle();
            this.codec = packet.codec();
            this.keyFrame = packet.keyFrame();
        }

        private boolean isComplete() {
            if (this.fragmentCount < 0 || this.fragments.size() < this.fragmentCount) {
                return false;
            }
            for (int index = 0; index < this.fragmentCount; index++) {
                if (!this.fragments.containsKey(index)) {
                    return false;
                }
            }
            return true;
        }

        // For each parity group with exactly one missing data shard, rebuild it: missing = parity XOR
        // (every other shard in the group). Groups are disjoint, so a single pass is sufficient.
        private void reconstructFromParity() {
            for (FecGroup group : this.parities.values()) {
                long endExclusive = Math.min((long) this.fragmentCount, (long) group.groupStart() + group.groupShardCount());
                int start = Math.max(0, group.groupStart());
                if (group.groupShardCount() <= 0 || start >= endExclusive || group.parity().length != group.shardLength()) {
                    continue;
                }

                int missingIndex = -1;
                int missingCount = 0;
                for (int index = start; index < endExclusive; index++) {
                    if (!this.fragments.containsKey(index)) {
                        missingIndex = index;
                        if (++missingCount > 1) {
                            break;
                        }
                    }
                }
                if (missingCount != 1) {
                    continue;
                }

                byte[] recovered = group.parity().clone();
                for (int index = start; index < endExclusive; index++) {
                    if (index == missingIndex) {
                        continue;
                    }
                    byte[] shard = this.fragments.get(index);
                    int limit = Math.min(shard.length, recovered.length);
                    for (int i = 0; i < limit; i++) {
                        recovered[i] ^= shard[i];
                    }
                }

                int trueLength = missingIndex == this.fragmentCount - 1
                    ? group.totalPayloadLength() - missingIndex * group.shardLength()
                    : group.shardLength();
                if (trueLength < 0 || trueLength > recovered.length) {
                    continue;
                }
                this.fragments.put(missingIndex, trueLength == recovered.length ? recovered : Arrays.copyOf(recovered, trueLength));
            }
        }

        private byte[] assemble() {
            if (!this.isComplete()) {
                return null;
            }
            int totalSize = 0;
            for (int index = 0; index < this.fragmentCount; index++) {
                totalSize += this.fragments.get(index).length;
            }
            byte[] assembled = new byte[totalSize];
            int offset = 0;
            for (int index = 0; index < this.fragmentCount; index++) {
                byte[] fragment = this.fragments.get(index);
                System.arraycopy(fragment, 0, assembled, offset, fragment.length);
                offset += fragment.length;
            }
            return assembled;
        }
    }

    private record FecGroup(int groupStart, int groupShardCount, int shardLength, int totalPayloadLength, byte[] parity) {
    }
}
