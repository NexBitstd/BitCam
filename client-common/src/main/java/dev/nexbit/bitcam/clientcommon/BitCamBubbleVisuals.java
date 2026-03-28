package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubblePreset;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;

public record BitCamBubbleVisuals(int backgroundArgb, int borderArgb) {
    public static BitCamBubbleVisuals fromStyle(BitCamBubbleStyle style) {
        BitCamBubblePreset preset = style == null ? BitCamBubblePreset.CLASSIC : style.preset();

        return switch (preset) {
            case CLASSIC -> new BitCamBubbleVisuals(0x24000000, 0xCFF7F7F7);
            case MATTE -> new BitCamBubbleVisuals(0x8A090909, 0xE0EFEFEF);
            case GLASS -> new BitCamBubbleVisuals(0x68304459, 0xD3D6EDFF);
            case NEON -> new BitCamBubbleVisuals(0x78020E15, 0xFF3BE6FF);
        };
    }
}
