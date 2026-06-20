package dev.nexbit.bitcam.fabric.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.blaze3d.platform.InputConstants;
import dev.nexbit.bitcam.client.render.BitCamBillboardRenderer;
import dev.nexbit.bitcam.client.ui.BitCamSettingsScreen;
import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
import dev.nexbit.bitcam.clientcommon.CameraCatalog;
import dev.nexbit.bitcam.clientcommon.runtime.BitCamClientSessionController;
import dev.nexbit.bitcam.clientcommon.runtime.BitCamClientUiHost;
import dev.nexbit.bitcam.fabric.network.FabricBitCamControlPayload;
import dev.nexbit.bitcam.fabric.network.FabricBitCamNetworking;
import dev.nexbit.bitcam.fabric.platform.FabricPlatformAccess;
import dev.nexbit.bitcam.fabric.render.FabricBitCamPlayerBubbleLayer;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class FabricBitCamClientRuntime {
    private static final String KEY_CATEGORY = "category.bitcam";
    private static final String KEY_TOGGLE = "key.bitcam.toggle_stream";
    private static final String KEY_SETTINGS = "key.bitcam.open_settings";

    private final FabricPlatformAccess platform;
    private final Minecraft client;
    private final BitCamClientSessionController sessionController;
    private final BitCamBillboardRenderer billboardRenderer;
    private final KeyMapping toggleKey;
    private final KeyMapping settingsKey;

    public FabricBitCamClientRuntime(String minecraftVersion) {
        this.platform = FabricPlatformAccess.createClient(minecraftVersion);
        this.client = Minecraft.getInstance();
        this.sessionController = new BitCamClientSessionController(
            this.platform,
            () -> this.client.player.getUUID(),
            new BitCamClientUiHost() {
                @Override
                public void showMessage(String message) {
                    FabricBitCamClientRuntime.this.sendMessage(message);
                }

                @Override
                public void openSettings(BitCamClientCoordinator coordinator) {
                    FabricBitCamClientRuntime.this.client.setScreen(new BitCamSettingsScreen(FabricBitCamClientRuntime.this.client.screen, coordinator));
                }
            },
            this::sendHello
        );
        this.billboardRenderer = new BitCamBillboardRenderer(this.client, this.sessionController::coordinator);
        this.toggleKey = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(KEY_TOGGLE, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KEY_CATEGORY)
        );
        this.settingsKey = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(KEY_SETTINGS, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KEY_CATEGORY)
        );
    }

    public void initialize() {
        // Start downloading platform camera natives immediately on game launch (background).
        CameraCatalog.prewarm(this.platform.configDirectory());

        FabricBitCamNetworking.bootstrapPayloadTypes();
        ClientPlayNetworking.registerGlobalReceiver(FabricBitCamControlPayload.TYPE, (payload, context) -> {
            if (payload.decodeSignalPacket() instanceof ServerWelcomeSignalPacket welcome) {
                this.client.execute(() -> this.sessionController.handleWelcome(welcome));
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (this.client.player != null) {
                this.sessionController.onJoin();
                this.sendHello();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            this.sessionController.onDisconnect();
            // DISCONNECT can fire on the Netty IO thread (e.g. a dropped/timed-out connection), but
            // close() releases GL textures and must run on the render thread.
            this.client.execute(this.billboardRenderer::close);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (this.toggleKey.consumeClick()) {
                this.sessionController.toggleStreaming();
            }
            while (this.settingsKey.consumeClick()) {
                this.sessionController.openSettings();
            }
            if (this.sessionController.shouldRetryHello(client.getConnection() != null)) {
                this.sendHello();
            }
            this.sessionController.pruneRemoteFrames(10_000L);
            this.billboardRenderer.tick();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            this.sessionController.close();
            this.billboardRenderer.close();
        });
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
            if (entityRenderer instanceof PlayerRenderer playerRenderer) {
                registrationHelper.register(new FabricBitCamPlayerBubbleLayer(playerRenderer, this.billboardRenderer));
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("bitcam")
                .then(ClientCommandManager.literal("toggle").executes(context -> {
                    this.sessionController.toggleStreaming();
                    return 1;
                }))
                .then(ClientCommandManager.literal("cameras").executes(context -> {
                    this.sessionController.listCameras();
                    return 1;
                }))
                .then(ClientCommandManager.literal("settings").executes(context -> {
                    this.sessionController.openSettings();
                    return 1;
                }))
                .then(ClientCommandManager.literal("camera")
                    .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(0)).executes(context -> {
                        this.sessionController.selectCamera(IntegerArgumentType.getInteger(context, "index"));
                        return 1;
                    })))
        ));
    }

    private void sendHello() {
        BitCamClientCoordinator coordinator = this.sessionController.coordinator();
        if (coordinator != null) {
            ClientPlayNetworking.send(FabricBitCamControlPayload.fromSignalPacket(coordinator.createHelloPacket()));
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
