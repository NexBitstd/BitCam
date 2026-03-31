package dev.nexbit.bitcam.neoforge;

import dev.nexbit.bitcam.common.BitCamBootstrap;
import dev.nexbit.bitcam.neoforge.network.NeoForgeBitCamControlPayload;
import dev.nexbit.bitcam.neoforge.platform.NeoForgePlatformAccess;
import dev.nexbit.bitcam.protocol.BitCamProtocol;
import dev.nexbit.bitcam.protocol.signal.ClientHelloSignalPacket;
import dev.nexbit.bitcam.server.BitCamServerHelper;
import dev.nexbit.bitcam.server.command.BitCamServerCommands;
import dev.nexbit.bitcam.servercommon.BitCamServerCoordinator;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class NeoForgeBitCamServerRuntime {
    private final String minecraftVersion;
    private BitCamServerCoordinator coordinator;

    public NeoForgeBitCamServerRuntime(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion;
    }

    public void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar(Integer.toString(BitCamProtocol.PROTOCOL_VERSION))
            .optional()
            .playBidirectional(NeoForgeBitCamControlPayload.TYPE, NeoForgeBitCamControlPayload.CODEC, this::handleControlPacket);
    }

    public void onServerStarted(ServerStartedEvent event) {
        NeoForgePlatformAccess platform = NeoForgePlatformAccess.createServer(this.minecraftVersion);
        BitCamBootstrap.bootstrapServer(platform);
        this.coordinator = new BitCamServerCoordinator(
            platform,
            (streamerId, radius) -> BitCamServerHelper.resolveViewers(event.getServer().getPlayerList().getPlayers(), streamerId, radius),
            (playerId, expression) -> BitCamServerHelper.hasPermission(event.getServer().getPlayerList().getPlayers(), playerId, expression)
        );
        this.coordinator.start();
    }

    public void onServerStopping(ServerStoppingEvent event) {
        if (this.coordinator != null) {
            this.coordinator.close();
            this.coordinator = null;
        }
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (this.coordinator != null && event.getEntity() instanceof ServerPlayer player) {
            this.coordinator.removeSession(player.getUUID());
        }
    }

    public void onRegisterCommands(RegisterCommandsEvent event) {
        BitCamServerCommands.register(event.getDispatcher(), () -> this.coordinator);
    }

    private void handleControlPacket(NeoForgeBitCamControlPayload payload, IPayloadContext context) {
        if (this.coordinator == null) {
            return;
        }
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(payload.decodeSignalPacket() instanceof ClientHelloSignalPacket hello)) {
            return;
        }
        this.coordinator.createWelcomePacket(player.getUUID(), hello)
            .ifPresent(welcome -> context.reply(NeoForgeBitCamControlPayload.fromSignalPacket(welcome)));
    }
}
