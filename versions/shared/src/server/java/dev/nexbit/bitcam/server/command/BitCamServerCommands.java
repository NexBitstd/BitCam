package dev.nexbit.bitcam.server.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.nexbit.bitcam.servercommon.BitCamServerCoordinator;
import dev.nexbit.bitcam.servercommon.BitCamServerDebugFormatter;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class BitCamServerCommands {
    private BitCamServerCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<BitCamServerCoordinator> coordinator) {
        dispatcher.register(
            Commands.literal("bitcam")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                    .executes(context -> {
                        sendDebug(context.getSource(), coordinator.get(), false);
                        return 1;
                    })
                    .then(Commands.literal("clients")
                        .executes(context -> {
                            sendDebug(context.getSource(), coordinator.get(), true);
                            return 1;
                        })))
        );
    }

    private static void sendDebug(CommandSourceStack source, BitCamServerCoordinator coordinator, boolean verbose) {
        if (coordinator == null || source.getServer() == null) {
            source.sendFailure(Component.literal("BitCam server coordinator is not running."));
            return;
        }

        List<String> lines = verbose
            ? BitCamServerDebugFormatter.clientLines(coordinator.debugSnapshot(), playerId -> {
                ServerPlayer player = source.getServer().getPlayerList().getPlayer(playerId);
                return player == null ? null : player.getGameProfile().getName();
            })
            : BitCamServerDebugFormatter.summaryLines(coordinator.debugSnapshot());

        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }
}
