package dev.nexbit.bitcam.common;

public final class BitCamBootstrap {
    private BitCamBootstrap() {
    }

    public static void bootstrapCommon(PlatformAccess platform) {
        platform.logger().info(formatMessage("common", platform));
    }

    public static void bootstrapClient(ClientPlatformAccess platform) {
        platform.logger().info(formatMessage("client", platform));
    }

    public static void bootstrapServer(ServerPlatformAccess platform) {
        platform.logger().info(formatMessage("server", platform));
    }

    private static String formatMessage(String entrypoint, PlatformAccess platform) {
        return "Bootstrapping "
            + BitCamMetadata.MOD_NAME
            + " "
            + entrypoint
            + " entrypoint on "
            + platform.loader().displayName()
            + " ["
            + platform.distribution().displayName()
            + "] for Minecraft "
            + platform.minecraftVersion()
            + " using config dir "
            + platform.configDirectory();
    }
}
