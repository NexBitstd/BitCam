package dev.nexbit.bitcam.fabric.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class FabricBitCamNetworking {
    private static boolean bootstrapped;

    private FabricBitCamNetworking() {
    }

    public static synchronized void bootstrapPayloadTypes() {
        if (bootstrapped) {
            return;
        }

        PayloadTypeRegistry.playC2S().register(FabricBitCamControlPayload.TYPE, FabricBitCamControlPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FabricBitCamControlPayload.TYPE, FabricBitCamControlPayload.CODEC);
        bootstrapped = true;
    }
}
