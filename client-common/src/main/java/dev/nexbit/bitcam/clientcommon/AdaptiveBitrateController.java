package dev.nexbit.bitcam.clientcommon;

/**
 * AIMD congestion controller for the outgoing stream. Viewers report how much of the stream they
 * actually received; this aggregates the worst report over each evaluation window and nudges a
 * bitrate {@code scale} in [{@value #MIN_SCALE}, 1.0] the encoder multiplies into its target bitrate.
 *
 * <p>The behaviour mirrors how video-call apps react to a degrading link: back off fast on loss
 * (multiplicative decrease) and probe back up slowly when the link is clean (additive increase). With
 * no viewers (no reports) the window loss stays 0, so the scale steadily climbs back to full quality.
 *
 * <p>{@code onReport} runs on the UDP receive thread while {@code pollScale} runs on the capture
 * thread, so the shared window state is guarded by {@code this}.
 */
final class AdaptiveBitrateController {
    static final float MIN_SCALE = 0.25F;
    private static final long EVALUATION_INTERVAL_NANOS = 1_000_000_000L;
    // Loss thresholds (as a fraction): below LOW we probe up, above HIGH we back off.
    private static final double LOW_LOSS = 0.02D;
    private static final double HIGH_LOSS = 0.05D;
    private static final float ADDITIVE_INCREASE = 0.08F;
    private static final float MAX_DECREASE = 0.5F;

    private volatile float scale = 1.0F;
    private double windowWorstLoss;
    private long lastEvaluationNanos;

    synchronized void onReport(int lossPermille) {
        double loss = Math.clamp(lossPermille / 1000.0D, 0.0D, 1.0D);
        this.windowWorstLoss = Math.max(this.windowWorstLoss, loss);
    }

    /**
     * Returns the current bitrate scale, recomputing it once per evaluation window. Safe to call
     * every captured frame — it only does work when the window elapses.
     */
    float pollScale(long nowNanos) {
        synchronized (this) {
            if (this.lastEvaluationNanos == 0L) {
                this.lastEvaluationNanos = nowNanos;
                return this.scale;
            }
            if ((nowNanos - this.lastEvaluationNanos) < EVALUATION_INTERVAL_NANOS) {
                return this.scale;
            }
            this.lastEvaluationNanos = nowNanos;

            double worstLoss = this.windowWorstLoss;
            this.windowWorstLoss = 0.0D;

            float updated = this.scale;
            if (worstLoss > HIGH_LOSS) {
                // Multiplicative decrease, deeper the worse the loss — drop quickly when congested.
                float factor = 1.0F - (float) Math.min(MAX_DECREASE, worstLoss);
                updated = this.scale * factor;
            } else if (worstLoss < LOW_LOSS) {
                updated = this.scale + ADDITIVE_INCREASE;
            }
            this.scale = Math.clamp(updated, MIN_SCALE, 1.0F);
            return this.scale;
        }
    }

    synchronized void reset() {
        this.scale = 1.0F;
        this.windowWorstLoss = 0.0D;
        this.lastEvaluationNanos = 0L;
    }
}
