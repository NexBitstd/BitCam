package dev.nexbit.bitcam.server;

import dev.nexbit.bitcam.common.BitCamPermissionExpressions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
//#if MC>=12111
//$$ import net.minecraft.server.permissions.Permission;
//$$ import net.minecraft.server.permissions.PermissionLevel;
//#endif

public final class BitCamServerHelper {
    private BitCamServerHelper() {}

    public static List<UUID> resolveViewers(List<ServerPlayer> players, UUID streamerId, int radius) {
        ServerPlayer streamer = findPlayer(players, streamerId);
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

    public static boolean hasPermission(List<ServerPlayer> players, UUID playerId, String permissionExpression) {
        ServerPlayer player = findPlayer(players, playerId);
        if (player == null) {
            return false;
        }

        return BitCamPermissionExpressions.allows(
            permissionExpression,
            level -> hasPermission(player.createCommandSourceStack(), level),
            ignored -> false
        );
    }

    private static boolean hasPermission(CommandSourceStack source, int level) {
        //#if MC>=12111
        //$$ return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
        //#else
        return source.hasPermission(level);
        //#endif
    }

    private static ServerPlayer findPlayer(List<ServerPlayer> players, UUID id) {
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(id)) {
                return player;
            }
        }
        return null;
    }
}
