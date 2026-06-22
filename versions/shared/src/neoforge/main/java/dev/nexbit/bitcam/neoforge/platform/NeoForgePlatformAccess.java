//#if NEOFORGE
//$$ package dev.nexbit.bitcam.neoforge.platform;
//$$
//$$ import dev.nexbit.bitcam.common.AbstractPlatformAccess;
//$$ import dev.nexbit.bitcam.common.BitCamDistribution;
//$$ import dev.nexbit.bitcam.common.BitCamLoader;
//$$ import dev.nexbit.bitcam.common.BitCamMetadata;
//$$ import dev.nexbit.bitcam.common.ClientPlatformAccess;
//$$ import dev.nexbit.bitcam.common.PlatformLogger;
//$$ import dev.nexbit.bitcam.common.ServerPlatformAccess;
//$$ import net.neoforged.fml.loading.FMLEnvironment;
//$$ import net.neoforged.fml.loading.FMLPaths;
//$$ import org.slf4j.Logger;
//$$ import org.slf4j.LoggerFactory;
//$$
//$$ public final class NeoForgePlatformAccess extends AbstractPlatformAccess implements ClientPlatformAccess, ServerPlatformAccess {
//$$     private static final Logger SLF4J_LOGGER = LoggerFactory.getLogger(BitCamMetadata.MOD_ID);
//$$     private static final PlatformLogger LOGGER = new PlatformLogger() {
//$$         @Override
//$$         public void info(String message) {
//$$             SLF4J_LOGGER.info(message);
//$$         }
//$$
//$$         @Override
//$$         public void warn(String message) {
//$$             SLF4J_LOGGER.warn(message);
//$$         }
//$$
//$$         @Override
//$$         public void error(String message) {
//$$             SLF4J_LOGGER.error(message);
//$$         }
//$$
//$$         @Override
//$$         public void error(String message, Throwable throwable) {
//$$             SLF4J_LOGGER.error(message, throwable);
//$$         }
//$$     };
//$$
//$$     public static NeoForgePlatformAccess create(String minecraftVersion) {
//$$         return new NeoForgePlatformAccess(resolveDistribution(), minecraftVersion);
//$$     }
//$$
//$$     public static NeoForgePlatformAccess createClient(String minecraftVersion) {
//$$         return new NeoForgePlatformAccess(BitCamDistribution.CLIENT, minecraftVersion);
//$$     }
//$$
//$$     public static NeoForgePlatformAccess createServer(String minecraftVersion) {
//$$         return new NeoForgePlatformAccess(BitCamDistribution.SERVER, minecraftVersion);
//$$     }
//$$
//$$     private NeoForgePlatformAccess(BitCamDistribution distribution, String minecraftVersion) {
//$$         super(BitCamLoader.NEOFORGE, distribution, minecraftVersion, FMLPaths.CONFIGDIR.get(), LOGGER);
//$$     }
//$$
//$$     private static BitCamDistribution resolveDistribution() {
        //#if MC>=12109
//$$         return FMLEnvironment.getDist().isClient() ? BitCamDistribution.CLIENT : BitCamDistribution.SERVER;
        //#else
//$$         return FMLEnvironment.dist.isClient() ? BitCamDistribution.CLIENT : BitCamDistribution.SERVER;
        //#endif
//$$     }
//$$ }
//#endif
