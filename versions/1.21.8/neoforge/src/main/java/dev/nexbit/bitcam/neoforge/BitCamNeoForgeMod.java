package dev.nexbit.bitcam.neoforge;

import dev.nexbit.bitcam.common.BitCamBootstrap;
import dev.nexbit.bitcam.common.BitCamMetadata;
import dev.nexbit.bitcam.neoforge.platform.NeoForgePlatformAccess;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(BitCamMetadata.MOD_ID)
public final class BitCamNeoForgeMod {
    private static final String MINECRAFT_VERSION = "1.21.8";
    private final NeoForgeBitCamServerRuntime serverRuntime = new NeoForgeBitCamServerRuntime(MINECRAFT_VERSION);
    private final NeoForgeBitCamClientRuntime clientRuntime;

    public BitCamNeoForgeMod(IEventBus modEventBus, ModContainer modContainer) {
        BitCamBootstrap.bootstrapCommon(NeoForgePlatformAccess.create(MINECRAFT_VERSION));
        this.clientRuntime = FMLEnvironment.dist.isClient() ? new NeoForgeBitCamClientRuntime(MINECRAFT_VERSION) : null;

        modEventBus.addListener(this.serverRuntime::registerPayloadHandlers);
        modEventBus.addListener(this::onClientSetup);

        NeoForge.EVENT_BUS.addListener(this.serverRuntime::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this.serverRuntime::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this.serverRuntime::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this.serverRuntime::onRegisterCommands);

        if (this.clientRuntime != null) {
            modEventBus.addListener(this.clientRuntime::registerClientPayloadHandlers);
            modEventBus.addListener(this.clientRuntime::registerKeyMappings);
            NeoForge.EVENT_BUS.addListener(this.clientRuntime::onClientLoggingIn);
            NeoForge.EVENT_BUS.addListener(this.clientRuntime::onClientLoggingOut);
            NeoForge.EVENT_BUS.addListener(this.clientRuntime::onClientTick);
            NeoForge.EVENT_BUS.addListener(this.clientRuntime::onRenderPlayer);
            NeoForge.EVENT_BUS.addListener(this.clientRuntime::onRegisterClientCommands);
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        BitCamBootstrap.bootstrapClient(NeoForgePlatformAccess.createClient(MINECRAFT_VERSION));
    }
}
