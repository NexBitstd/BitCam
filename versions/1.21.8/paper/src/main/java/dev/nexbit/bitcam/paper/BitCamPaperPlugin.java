package dev.nexbit.bitcam.paper;

import dev.nexbit.bitcam.common.BitCamBootstrap;
import dev.nexbit.bitcam.paper.platform.PaperPlatformAccess;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class BitCamPaperPlugin extends JavaPlugin {
    private static final String MINECRAFT_VERSION = "1.21.8";
    private PaperBitCamServerRuntime serverRuntime;

    @Override
    public void onEnable() {
        BitCamBootstrap.bootstrapCommon(new PaperPlatformAccess(this, MINECRAFT_VERSION));
        this.serverRuntime = new PaperBitCamServerRuntime(this, MINECRAFT_VERSION);
        this.serverRuntime.initialize();
    }

    @Override
    public void onDisable() {
        if (this.serverRuntime != null) {
            this.serverRuntime.close();
            this.serverRuntime = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"bitcam".equalsIgnoreCase(command.getName()) || this.serverRuntime == null) {
            return false;
        }

        return this.serverRuntime.handleCommand(sender, args);
    }
}
