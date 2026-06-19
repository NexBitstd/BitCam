package dev.nexbit.bitcam.clientcommon;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Per-streamer jitter buffer.
 *
 * <p>UDP delivery is bursty: several frames arrive back-to-back, then a gap. Showing each frame the
 * instant it arrives makes playback stutter even when throughput is fine. This buffers a small
 * cushion of completed frames and releases them on a steady clock derived from their capture
 * timestamps, so motion plays back smoothly regardless of network jitter.
 *
 * <p>The capture timestamps come from the streamer's wall clock and are never synchronised with the
 * viewer's; only the <em>differences</em> between consecutive frames are used, anchored to the local
 * clock when playback starts. Residual drift and catch-up bursts are corrected by snapping to the
 * newest buffered frame whenever playback falls more than {@link #MAX_LAG_MILLIS} behind.
 */
final class RemoteStreamPlayout {
    // Frames to accumulate before playback starts — the cushion that absorbs jitter.
    private static final int START_DEPTH = 2;
    // Hard cap on buffered frames so a stalled / off-screen stream can't grow unbounded.
    private static final int MAX_BUFFERED = 8;
    // If playback drifts this far behind the newest buffered frame, skip ahead to resync.
    private static final long MAX_LAG_MILLIS = 350L;

    private final ConcurrentSkipListMap<Integer, DecodedFrame> buffer = new ConcurrentSkipListMap<>();

    private boolean playing;
    private long playoutAnchorNanos;
    private long captureAnchorMillis;
    private DecodedFrame current;
    private volatile long lastOfferMillis = System.currentTimeMillis();

    synchronized void offer(DecodedFrame frame) {
        this.lastOfferMillis = System.currentTimeMillis();
        // Drop late / reordered duplicates of frames we've already played past.
        if (this.current != null && frame.frameId() <= this.current.frameId()) {
            return;
        }
        this.buffer.put(frame.frameId(), frame);
        while (this.buffer.size() > MAX_BUFFERED) {
            this.buffer.pollFirstEntry();
        }
    }

    /** Returns the frame that should be displayed now, advancing the playout clock. */
    synchronized DecodedFrame current(long nowNanos) {
        if (!this.playing) {
            if (this.buffer.size() < START_DEPTH) {
                return this.current;
            }
            this.startPlayback(nowNanos, this.buffer.firstEntry().getValue());
            return this.current;
        }

        long elapsedMillis = (nowNanos - this.playoutAnchorNanos) / 1_000_000L;
        long targetCaptureMillis = this.captureAnchorMillis + elapsedMillis;

        DecodedFrame advanced = this.current;
        for (DecodedFrame candidate : this.buffer.values()) {
            if (candidate.captureTimeMillis() > targetCaptureMillis) {
                break;
            }
            advanced = candidate;
        }

        DecodedFrame newest = this.buffer.isEmpty() ? advanced : this.buffer.lastEntry().getValue();
        if (newest != null && advanced != null
            && newest.captureTimeMillis() - advanced.captureTimeMillis() > MAX_LAG_MILLIS) {
            // Fell too far behind (catch-up burst or clock drift): jump to newest and re-anchor.
            this.startPlayback(nowNanos, newest);
            return this.current;
        }

        if (advanced != null) {
            this.current = advanced;
            this.buffer.headMap(advanced.frameId(), false).clear();
        }
        return this.current;
    }

    /** Returns the current frame without advancing the clock (for housekeeping snapshots). */
    synchronized DecodedFrame peekCurrent() {
        if (this.current != null) {
            return this.current;
        }
        Map.Entry<Integer, DecodedFrame> first = this.buffer.firstEntry();
        return first == null ? null : first.getValue();
    }

    long lastOfferMillis() {
        return this.lastOfferMillis;
    }

    private void startPlayback(long nowNanos, DecodedFrame frame) {
        this.playing = true;
        this.playoutAnchorNanos = nowNanos;
        this.captureAnchorMillis = frame.captureTimeMillis();
        this.current = frame;
        this.buffer.headMap(frame.frameId(), false).clear();
    }
}
