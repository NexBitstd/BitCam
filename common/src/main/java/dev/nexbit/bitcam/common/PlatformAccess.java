package dev.nexbit.bitcam.common;

import java.nio.file.Path;

public interface PlatformAccess {
    BitCamLoader loader();

    BitCamDistribution distribution();

    String minecraftVersion();

    Path configDirectory();

    PlatformLogger logger();
}
