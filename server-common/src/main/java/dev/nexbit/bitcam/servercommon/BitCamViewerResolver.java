package dev.nexbit.bitcam.servercommon;

import java.util.Collection;
import java.util.UUID;

@FunctionalInterface
public interface BitCamViewerResolver {
    Collection<UUID> resolveViewers(UUID streamerId, int radius);
}
