package dev.nexbit.bitcam.neoforge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.blaze3d.platform.InputConstants;
import dev.nexbit.bitcam.client.render.BitCamBillboardRenderer;
import dev.nexbit.bitcam.client.ui.BitCamSettingsScreen;
import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
import dev.nexbit.bitcam.clientcommon.CameraCatalog;
import dev.nexbit.bitcam.clientcommon.runtime.BitCamClientSessionController;
import dev.nexbit.bitcam.clientcommon.runtime.BitCamClientUiHost;
import dev.nexbit.bitcam.neoforge.network.NeoForgeBitCamControlPayload;
import dev.nexbit.bitcam.neoforge.platform.NeoForgePlatformAccess;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.lwjgl.glfw.GLFW;

public final class NeoForgeBitCamClientRuntime {
    private static final String KEY_CATEGORY = "category.bitcam";
    private static final String KEY_TOGGLE = "key.bitcam.toggle_stream";
    private static final String KEY_SETTINGS = "key.bitcam.open_settings";

    private final NeoForgePlatformAccess platform;
    private final Minecraft client = Minecraft.getInstance();
    private final KeyMapping toggleKey = new KeyMapping(KEY_TOGGLE, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KEY_CATEGORY);
    private final KeyMapping settingsKey = new KeyMapping(KEY_SETTINGS, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KEY_CATEGORY);
    private final BitCamClientSessionController sessionController;
    private final BitCamBillboardRenderer billboardRenderer;

    public NeoForgeBitCamClientRuntime(String minecraftVersion) {
        this.platform = NeoForgePlatformAccess.createClient(minecraftVersion);
        // Start downloading platform camera natives immediately on game launch (background).
        CameraCatalog.prewarm(this.platform.configDirectory());
        this.sessionController = new BitCamClientSessionController(
            this.platform,
            () -> this.client.player.getUUID(),
            new BitCamClientUiHost() {
                @Override
                public void showMessage(String message) {
                    NeoForgeBitCamClientRuntime.this.sendMessage(message);
                }

                @Override
                public void openSettings(BitCamClientCoordinator coordinator) {
                    NeoForgeBitCamClientRuntime.this.client.setScreen(new BitCamSettingsScreen(NeoForgeBitCamClientRuntime.this.client.screen, coordinator));
                }
            },
            this::sendHello
        );
        this.billboardRenderer = new BitCamBillboardRenderer(this.client, this.sessionController::coordinator);
    }

    public void registerClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(NeoForgeBitCamControlPayload.TYPE, this::handleControlPacket);
    }

    public void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(this.toggleKey);
        event.register(this.settingsKey);
    }

    public void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        this.sessionController.onJoin();
        this.sendHello();
    }

    public void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        this.sessionController.onDisconnect();
        this.billboardRenderer.close();
    }

    public void onClientTick(ClientTickEvent.Post event) {
        while (this.toggleKey.consumeClick()) {
            this.sessionController.toggleStreaming();
        }
        while (this.settingsKey.consumeClick()) {
            this.sessionController.openSettings();
        }
        if (this.sessionController.shouldRetryHello(this.client.getConnection() != null)) {
            this.sendHello();
        }
        this.sessionController.pruneRemoteFrames(10_000L);
        this.billboardRenderer.tick();
    }

    public void onRenderPlayer(RenderPlayerEvent.Post event) {
        if (this.client.level == null) {
            return;
        }
        Entity entity = this.client.level.getEntity(event.getRenderState().id);
        if (!(entity instanceof AbstractClientPlayer player)) {
            return;
        }
        this.billboardRenderer.renderPlayerBubble(
            event.getPoseStack(), player,
            player.getBbHeight(),
            event.getRenderState().bodyRot,
            event.getRenderState().yRot,
            event.getRenderState().xRot,
            event.getRenderer().getModel().head
        );
    }

    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("bitcam")
                .then(Commands.literal("toggle").executes(context -> {
                    this.sessionController.toggleStreaming();
                    return 1;
                }))
                .then(Commands.literal("cameras").executes(context -> {
                    this.sessionController.listCameras();
                    return 1;
                }))
                .then(Commands.literal("settings").executes(context -> {
                    this.sessionController.openSettings();
                    return 1;
                }))
                .then(Commands.literal("camera")
                    .then(Commands.argument("index", IntegerArgumentType.integer(0)).executes(context -> {
                        this.sessionController.selectCamera(IntegerArgumentType.getInteger(context, "index"));
                        return 1;
                    })))
        );
    }

    private void handleControlPacket(NeoForgeBitCamControlPayload payload, IPayloadContext context) {
        if (payload.decodeSignalPacket() instanceof ServerWelcomeSignalPacket welcome) {
            this.client.execute(() -> this.sessionController.handleWelcome(welcome));
        }
    }

    private void sendHello() {
        BitCamClientCoordinator coordinator = this.sessionController.coordinator();
        if (coordinator != null) {
            ClientPacketDistributor.sendToServer(NeoForgeBitCamControlPayload.fromSignalPacket(coordinator.createHelloPacket()));
        }
    }

    private void sendMessage(String message) {
        if (this.client.player != null) {
            this.client.player.displayClientMessage(Component.literal(message), false);
        } else {
            this.platform.logger().info(message);
        }
    }
}
