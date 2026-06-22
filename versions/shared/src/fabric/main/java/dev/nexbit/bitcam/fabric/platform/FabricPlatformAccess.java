//#if FABRIC
package dev.nexbit.bitcam.fabric.platform;

import dev.nexbit.bitcam.common.AbstractPlatformAccess;
import dev.nexbit.bitcam.common.BitCamDistribution;
import dev.nexbit.bitcam.common.BitCamLoader;
import dev.nexbit.bitcam.common.BitCamMetadata;
import dev.nexbit.bitcam.common.ClientPlatformAccess;
import dev.nexbit.bitcam.common.PlatformLogger;
import dev.nexbit.bitcam.common.ServerPlatformAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FabricPlatformAccess extends AbstractPlatformAccess implements ClientPlatformAccess, ServerPlatformAccess {
    private static final Logger SLF4J_LOGGER = LoggerFactory.getLogger(BitCamMetadata.MOD_ID);
    private static final PlatformLogger LOGGER = new PlatformLogger() {
        @Override
        public void info(String message) {
            SLF4J_LOGGER.info(message);
        }

        @Override
        public void warn(String message) {
            SLF4J_LOGGER.warn(message);
        }

        @Override
        public void error(String message) {
            SLF4J_LOGGER.error(message);
        }

        @Override
        public void error(String message, Throwable throwable) {
            SLF4J_LOGGER.error(message, throwable);
        }
    };

    public static FabricPlatformAccess create(String minecraftVersion) {
        FabricLoader loader = FabricLoader.getInstance();
        return new FabricPlatformAccess(resolveDistribution(loader.getEnvironmentType()), minecraftVersion);
    }

    public static FabricPlatformAccess createClient(String minecraftVersion) {
        return new FabricPlatformAccess(BitCamDistribution.CLIENT, minecraftVersion);
    }

    public static FabricPlatformAccess createServer(String minecraftVersion) {
        return new FabricPlatformAccess(BitCamDistribution.SERVER, minecraftVersion);
    }

    private FabricPlatformAccess(BitCamDistribution distribution, String minecraftVersion) {
        super(BitCamLoader.FABRIC, distribution, minecraftVersion, FabricLoader.getInstance().getConfigDir(), LOGGER);
    }

    private static BitCamDistribution resolveDistribution(EnvType environmentType) {
        return environmentType == EnvType.CLIENT ? BitCamDistribution.CLIENT : BitCamDistribution.SERVER;
    }
}
//#endif
