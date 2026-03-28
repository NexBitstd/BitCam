package dev.nexbit.bitcam.neoforge;

import dev.nexbit.bitcam.common.BitCamBootstrap;
import dev.nexbit.bitcam.neoforge.network.NeoForgeBitCamControlPayload;
import dev.nexbit.bitcam.neoforge.platform.NeoForgePlatformAccess;
import dev.nexbit.bitcam.protocol.BitCamProtocol;
import dev.nexbit.bitcam.protocol.signal.ClientHelloSignalPacket;
import dev.nexbit.bitcam.servercommon.BitCamServerCoordinator;
import dev.nexbit.bitcam.servercommon.BitCamServerDebugFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
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
            (streamerId, radius) -> this.resolveViewers(event.getServer().getPlayerList().getPlayers(), streamerId, radius)
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
        event.getDispatcher().register(
            Commands.literal("bitcam")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug").executes(context -> {
                    this.sendDebug(context.getSource(), false);
                    return 1;
                }).then(Commands.literal("clients").executes(context -> {
                    this.sendDebug(context.getSource(), true);
                    return 1;
                })))
        );
    }

    private List<UUID> resolveViewers(List<ServerPlayer> players, UUID streamerId, int radius) {
        ServerPlayer streamer = null;
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(streamerId)) {
                streamer = player;
                break;
            }
        }

        if (streamer == null) {
            return List.of();
        }

        double radiusSquared = (double) radius * radius;
        List<UUID> viewers = new ArrayList<>();
        for (ServerPlayer viewer : players) {
            if (viewer.getUUID().equals(streamerId)) {
                continue;
            }

            if (viewer.level() != streamer.level()) {
                continue;
            }

            if (viewer.distanceToSqr(streamer) > radiusSquared) {
                continue;
            }

            viewers.add(viewer.getUUID());
        }

        return viewers;
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

        this.coordinator
            .createWelcomePacket(player.getUUID(), hello)
            .ifPresent(welcome -> context.reply(NeoForgeBitCamControlPayload.fromSignalPacket(welcome)));
    }

    private void sendDebug(CommandSourceStack source, boolean verbose) {
        if (this.coordinator == null || source.getServer() == null) {
            source.sendFailure(Component.literal("BitCam server coordinator is not running."));
            return;
        }

        List<String> lines = verbose
            ? BitCamServerDebugFormatter.clientLines(this.coordinator.debugSnapshot(), playerId -> {
                ServerPlayer player = source.getServer().getPlayerList().getPlayer(playerId);
                return player == null ? null : player.getGameProfile().getName();
            })
            : BitCamServerDebugFormatter.summaryLines(this.coordinator.debugSnapshot());

        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }
}
