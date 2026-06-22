//#if FABRIC
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

        //#if MC>=260100
        //$$ PayloadTypeRegistry.serverboundPlay().register(FabricBitCamControlPayload.TYPE, FabricBitCamControlPayload.CODEC);
        //$$ PayloadTypeRegistry.clientboundPlay().register(FabricBitCamControlPayload.TYPE, FabricBitCamControlPayload.CODEC);
        //#else
        PayloadTypeRegistry.playC2S().register(FabricBitCamControlPayload.TYPE, FabricBitCamControlPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FabricBitCamControlPayload.TYPE, FabricBitCamControlPayload.CODEC);
        //#endif
        bootstrapped = true;
    }
}
//#endif
