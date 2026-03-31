package dev.nexbit.bitcam.common;

import java.util.UUID;

@FunctionalInterface
public interface BitCamPlayerPermissionChecker {
    boolean hasPermission(UUID playerId, String permissionExpression);
}
