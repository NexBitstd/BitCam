//#if FABRIC
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
//#if MC>=260100
//$$ import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
//#else
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
//#endif
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//#if MC>=260100
//$$ import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//#else
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//#endif
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//#if MC>=260100
//$$ import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
//#else
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
//#endif
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//#if MC>=12109
//$$ import net.minecraft.client.renderer.entity.player.AvatarRenderer;
//#else
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
//#endif
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class FabricBitCamClientRuntime {
    //#if MC>=12109
    //$$ private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
        //#if MC>=12111
    //$$     net.minecraft.resources.Identifier.fromNamespaceAndPath("bitcam", "main"));
        //#else
    //$$     net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bitcam", "main"));
        //#endif
    //#else
    private static final String KEY_CATEGORY = "category.bitcam";
    //#endif
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
        //#if MC>=260100
        //$$ this.toggleKey = KeyMappingHelper.registerKeyMapping(createKeyMapping(KEY_TOGGLE, GLFW.GLFW_KEY_V));
        //$$ this.settingsKey = KeyMappingHelper.registerKeyMapping(createKeyMapping(KEY_SETTINGS, GLFW.GLFW_KEY_B));
        //#else
        this.toggleKey = KeyBindingHelper.registerKeyBinding(createKeyMapping(KEY_TOGGLE, GLFW.GLFW_KEY_V));
        this.settingsKey = KeyBindingHelper.registerKeyBinding(createKeyMapping(KEY_SETTINGS, GLFW.GLFW_KEY_B));
        //#endif
    }

    private static KeyMapping createKeyMapping(String translationKey, int glfwKey) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, glfwKey, KEY_CATEGORY);
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
        //#if MC>=260100
        //$$ LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
        //#else
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
        //#endif
            //#if MC>=12109
            //$$ if (entityRenderer instanceof AvatarRenderer playerRenderer) {
            //$$     registrationHelper.register(new FabricBitCamPlayerBubbleLayer(playerRenderer, this.billboardRenderer));
            //$$ }
            //#else
            if (entityRenderer instanceof PlayerRenderer playerRenderer) {
                registrationHelper.register(new FabricBitCamPlayerBubbleLayer(playerRenderer, this.billboardRenderer));
            }
            //#endif
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            //#if MC>=260100
            //$$ ClientCommands.literal("bitcam")
            //$$     .then(ClientCommands.literal("toggle").executes(context -> {
            //$$         this.sessionController.toggleStreaming();
            //$$         return 1;
            //$$     }))
            //$$     .then(ClientCommands.literal("cameras").executes(context -> {
            //$$         this.sessionController.listCameras();
            //$$         return 1;
            //$$     }))
            //$$     .then(ClientCommands.literal("settings").executes(context -> {
            //$$         this.sessionController.openSettings();
            //$$         return 1;
            //$$     }))
            //$$     .then(ClientCommands.literal("camera")
            //$$         .then(ClientCommands.argument("index", IntegerArgumentType.integer(0)).executes(context -> {
            //$$             this.sessionController.selectCamera(IntegerArgumentType.getInteger(context, "index"));
            //$$             return 1;
            //$$         })))
            //#else
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
            //#endif
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
            //#if MC>=260100
            //$$ this.client.player.sendSystemMessage(Component.literal(message));
            //#else
            this.client.player.displayClientMessage(Component.literal(message), false);
            //#endif
        } else {
            this.platform.logger().info(message);
        }
    }
}
//#endif
