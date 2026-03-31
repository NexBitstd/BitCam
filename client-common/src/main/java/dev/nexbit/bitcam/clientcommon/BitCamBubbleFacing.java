package dev.nexbit.bitcam.clientcommon;

public record BitCamBubbleFacing(float yawDegrees, float pitchDegrees) {
    public static BitCamBubbleFacing faceViewer(
        double anchorX,
        double anchorY,
        double anchorZ,
        double viewerX,
        double viewerY,
        double viewerZ
    ) {
        double dx = viewerX - anchorX;
        double dy = viewerY - anchorY;
        double dz = viewerZ - anchorZ;
        double horizontalDistance = Math.sqrt((dx * dx) + (dz * dz));
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(1.0E-6D, horizontalDistance)));
        return new BitCamBubbleFacing(yaw, pitch);
    }
}
