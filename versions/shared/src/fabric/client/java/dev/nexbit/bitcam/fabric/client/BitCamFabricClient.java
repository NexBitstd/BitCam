//#if FABRIC
package dev.nexbit.bitcam.fabric.client;

import dev.nexbit.bitcam.common.BitCamBootstrap;
import dev.nexbit.bitcam.fabric.platform.FabricPlatformAccess;
import dev.nexbit.bitcam.generated.BitCamBuildInfo;
import net.fabricmc.api.ClientModInitializer;

public final class BitCamFabricClient implements ClientModInitializer {
    private static final String MINECRAFT_VERSION = BitCamBuildInfo.MINECRAFT_VERSION;
    private final FabricBitCamClientRuntime clientRuntime = new FabricBitCamClientRuntime(MINECRAFT_VERSION);

    @Override
    public void onInitializeClient() {
        BitCamBootstrap.bootstrapClient(FabricPlatformAccess.createClient(MINECRAFT_VERSION));
        this.clientRuntime.initialize();
    }
}
//#endif
