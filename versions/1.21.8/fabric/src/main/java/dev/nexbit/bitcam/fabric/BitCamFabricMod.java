package dev.nexbit.bitcam.fabric;

import dev.nexbit.bitcam.common.BitCamBootstrap;
import dev.nexbit.bitcam.fabric.platform.FabricPlatformAccess;
import net.fabricmc.api.ModInitializer;

public final class BitCamFabricMod implements ModInitializer {
    private static final String MINECRAFT_VERSION = "1.21.8";
    private final FabricBitCamServerRuntime serverRuntime = new FabricBitCamServerRuntime(MINECRAFT_VERSION);

    @Override
    public void onInitialize() {
        BitCamBootstrap.bootstrapCommon(FabricPlatformAccess.create(MINECRAFT_VERSION));
        this.serverRuntime.initialize();
    }
}
