//#if FABRIC
package dev.nexbit.bitcam.fabric;

import dev.nexbit.bitcam.common.BitCamBootstrap;
import dev.nexbit.bitcam.fabric.network.FabricBitCamControlPayload;
import dev.nexbit.bitcam.fabric.network.FabricBitCamNetworking;
import dev.nexbit.bitcam.fabric.platform.FabricPlatformAccess;
import dev.nexbit.bitcam.protocol.signal.ClientHelloSignalPacket;
import dev.nexbit.bitcam.server.BitCamServerHelper;
import dev.nexbit.bitcam.server.command.BitCamServerCommands;
import dev.nexbit.bitcam.servercommon.BitCamServerCoordinator;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class FabricBitCamServerRuntime {
    private final String minecraftVersion;
    private BitCamServerCoordinator coordinator;

    public FabricBitCamServerRuntime(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion;
    }

    public void initialize() {
        FabricBitCamNetworking.bootstrapPayloadTypes();
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (this.coordinator != null) {
                this.coordinator.close();
                this.coordinator = null;
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (this.coordinator != null) {
                this.coordinator.removeSession(handler.player.getUUID());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(FabricBitCamControlPayload.TYPE, (payload, context) -> {
            if (this.coordinator == null) {
                return;
            }
            if (!(payload.decodeSignalPacket() instanceof ClientHelloSignalPacket hello)) {
                return;
            }
            ServerPlayer player = context.player();
            this.coordinator.createWelcomePacket(player.getUUID(), hello)
                .ifPresent(welcome -> ServerPlayNetworking.send(player, FabricBitCamControlPayload.fromSignalPacket(welcome)));
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            BitCamServerCommands.register(dispatcher, () -> this.coordinator)
        );
    }

    private void onServerStarted(MinecraftServer server) {
        FabricPlatformAccess platform = FabricPlatformAccess.createServer(this.minecraftVersion);
        BitCamBootstrap.bootstrapServer(platform);
        this.coordinator = new BitCamServerCoordinator(
            platform,
            (streamerId, radius) -> BitCamServerHelper.resolveViewers(server.getPlayerList().getPlayers(), streamerId, radius),
            (playerId, expression) -> BitCamServerHelper.hasPermission(server.getPlayerList().getPlayers(), playerId, expression)
        );
        this.coordinator.start();
    }
}
//#endif
