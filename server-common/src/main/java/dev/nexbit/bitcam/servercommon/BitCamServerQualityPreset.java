package dev.nexbit.bitcam.servercommon;

import dev.nexbit.bitcam.protocol.signal.BitCamStreamQualityProfile;

public record BitCamServerQualityPreset(BitCamStreamQualityProfile profile, String permissionExpression) {
    public BitCamServerQualityPreset {
        profile = profile == null
            ? new BitCamStreamQualityProfile("default", "Default", 320, 180, 15, 0.92F)
            : profile;
        permissionExpression = permissionExpression == null ? "" : permissionExpression.trim();
    }
}
