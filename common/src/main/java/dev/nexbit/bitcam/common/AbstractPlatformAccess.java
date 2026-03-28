package dev.nexbit.bitcam.common;

import java.nio.file.Path;

public abstract class AbstractPlatformAccess implements PlatformAccess {
    private final BitCamLoader loader;
    private final BitCamDistribution distribution;
    private final String minecraftVersion;
    private final Path configDirectory;
    private final PlatformLogger logger;

    protected AbstractPlatformAccess(
        BitCamLoader loader,
        BitCamDistribution distribution,
        String minecraftVersion,
        Path configDirectory,
        PlatformLogger logger
    ) {
        this.loader = loader;
        this.distribution = distribution;
        this.minecraftVersion = minecraftVersion;
        this.configDirectory = configDirectory;
        this.logger = logger;
    }

    @Override
    public final BitCamLoader loader() {
        return this.loader;
    }

    @Override
    public final BitCamDistribution distribution() {
        return this.distribution;
    }

    @Override
    public final String minecraftVersion() {
        return this.minecraftVersion;
    }

    @Override
    public final Path configDirectory() {
        return this.configDirectory;
    }

    @Override
    public final PlatformLogger logger() {
        return this.logger;
    }
}
