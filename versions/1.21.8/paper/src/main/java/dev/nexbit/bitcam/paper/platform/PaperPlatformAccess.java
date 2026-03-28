package dev.nexbit.bitcam.paper.platform;

import dev.nexbit.bitcam.common.AbstractPlatformAccess;
import dev.nexbit.bitcam.common.BitCamDistribution;
import dev.nexbit.bitcam.common.BitCamLoader;
import dev.nexbit.bitcam.common.PlatformLogger;
import dev.nexbit.bitcam.common.ServerPlatformAccess;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperPlatformAccess extends AbstractPlatformAccess implements ServerPlatformAccess {
    public PaperPlatformAccess(JavaPlugin plugin, String minecraftVersion) {
        super(
            BitCamLoader.PAPER,
            BitCamDistribution.SERVER,
            minecraftVersion,
            plugin.getDataFolder().toPath(),
            createLogger(plugin.getLogger())
        );
    }

    private static PlatformLogger createLogger(Logger logger) {
        return new PlatformLogger() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warning(message);
            }

            @Override
            public void error(String message) {
                logger.severe(message);
            }

            @Override
            public void error(String message, Throwable throwable) {
                logger.severe(message + " :: " + throwable.getMessage());
            }
        };
    }
}
