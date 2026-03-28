package dev.nexbit.bitcam.fabric;

import dev.nexbit.bitcam.common.BitCamBootstrap;
import dev.nexbit.bitcam.fabric.network.FabricBitCamControlPayload;
import dev.nexbit.bitcam.fabric.network.FabricBitCamNetworking;
import dev.nexbit.bitcam.fabric.platform.FabricPlatformAccess;
import dev.nexbit.bitcam.protocol.signal.ClientHelloSignalPacket;
import dev.nexbit.bitcam.servercommon.BitCamServerCoordinator;
import dev.nexbit.bitcam.servercommon.BitCamServerDebugFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> this.removeSession(handler.player.getUUID()));
        ServerPlayNetworking.registerGlobalReceiver(FabricBitCamControlPayload.TYPE, (payload, context) -> this.handleControlPacket(payload, context.player()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("bitcam")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug").executes(context -> {
                    this.sendDebug(context.getSource(), false);
                    return 1;
                }).then(Commands.literal("clients").executes(context -> {
                    this.sendDebug(context.getSource(), true);
                    return 1;
                })))
        ));
    }

    private void onServerStarted(MinecraftServer server) {
        FabricPlatformAccess platform = FabricPlatformAccess.createServer(this.minecraftVersion);
        BitCamBootstrap.bootstrapServer(platform);
        this.coordinator = new BitCamServerCoordinator(platform, (streamerId, radius) -> this.resolveViewers(server, streamerId, radius));
        this.coordinator.start();
    }

    private void onServerStopping(MinecraftServer server) {
        if (this.coordinator != null) {
            this.coordinator.close();
            this.coordinator = null;
        }
    }

    private List<UUID> resolveViewers(MinecraftServer server, UUID streamerId, int radius) {
        ServerPlayer streamer = server.getPlayerList().getPlayer(streamerId);
        if (streamer == null) {
            return List.of();
        }

        double radiusSquared = (double) radius * radius;
        List<UUID> viewers = new ArrayList<>();

        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
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

    private void handleControlPacket(FabricBitCamControlPayload payload, ServerPlayer player) {
        if (this.coordinator == null) {
            return;
        }

        if (!(payload.decodeSignalPacket() instanceof ClientHelloSignalPacket hello)) {
            return;
        }

        this.coordinator
            .createWelcomePacket(player.getUUID(), hello)
            .ifPresent(welcome -> ServerPlayNetworking.send(player, FabricBitCamControlPayload.fromSignalPacket(welcome)));
    }

    private void removeSession(UUID playerId) {
        if (this.coordinator != null) {
            this.coordinator.removeSession(playerId);
        }
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
