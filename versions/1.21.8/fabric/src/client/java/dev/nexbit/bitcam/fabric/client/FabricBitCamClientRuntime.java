package dev.nexbit.bitcam.fabric.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.blaze3d.platform.InputConstants;
import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
import dev.nexbit.bitcam.clientcommon.CameraDeviceInfo;
import dev.nexbit.bitcam.common.PlatformLogger;
import dev.nexbit.bitcam.fabric.network.FabricBitCamControlPayload;
import dev.nexbit.bitcam.fabric.network.FabricBitCamNetworking;
import dev.nexbit.bitcam.fabric.platform.FabricPlatformAccess;
import dev.nexbit.bitcam.fabric.client.ui.BitCamSettingsScreen;
import dev.nexbit.bitcam.fabric.render.BitCamBillboardRenderer;
import dev.nexbit.bitcam.fabric.render.FabricBitCamPlayerBubbleLayer;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
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
    private final PlatformLogger logger;
    private final BitCamBillboardRenderer billboardRenderer;
    private final KeyMapping toggleKey;
    private final KeyMapping settingsKey;

    private BitCamClientCoordinator coordinator;
    private int helloRetryCooldown;
    private boolean setupPromptShownThisJoin;

    public FabricBitCamClientRuntime(String minecraftVersion) {
        this.platform = FabricPlatformAccess.createClient(minecraftVersion);
        this.client = Minecraft.getInstance();
        this.logger = this.platform.logger();
        this.billboardRenderer = new BitCamBillboardRenderer(this.client, () -> this.coordinator);
        this.toggleKey = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(KEY_TOGGLE, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KEY_CATEGORY)
        );
        this.settingsKey = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(KEY_SETTINGS, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KEY_CATEGORY)
        );
    }

    public void initialize() {
        FabricBitCamNetworking.bootstrapPayloadTypes();
        ClientPlayNetworking.registerGlobalReceiver(FabricBitCamControlPayload.TYPE, (payload, context) -> this.handleControlPacket(payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> this.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> this.onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            this.closeCoordinator();
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
                    this.toggleStreaming();
                    return 1;
                }))
                .then(ClientCommandManager.literal("cameras").executes(context -> {
                    this.listCameras();
                    return 1;
                }))
                .then(ClientCommandManager.literal("settings").executes(context -> {
                    this.openSettings();
                    return 1;
                }))
                .then(ClientCommandManager.literal("camera")
                    .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(0)).executes(context -> {
                        this.selectCamera(IntegerArgumentType.getInteger(context, "index"));
                        return 1;
                    })))
        ));
    }

    private void onJoin() {
        if (this.client.player == null) {
            return;
        }

        this.closeCoordinator();
        this.coordinator = new BitCamClientCoordinator(this.platform, () -> this.client.player.getUUID());
        this.sendHello();
        this.helloRetryCooldown = 20;
        this.setupPromptShownThisJoin = false;
    }

    private void onDisconnect() {
        this.closeCoordinator();
        this.billboardRenderer.close();
        this.setupPromptShownThisJoin = false;
    }

    private void handleControlPacket(FabricBitCamControlPayload payload) {
        if (payload.decodeSignalPacket() instanceof ServerWelcomeSignalPacket welcome) {
            this.client.execute(() -> {
                if (this.coordinator == null) {
                    return;
                }

                this.coordinator.handleWelcome(welcome);
                this.sendMessage("BitCam received UDP endpoint " + welcome.udpHost() + ":" + welcome.udpPort());
                if (!this.setupPromptShownThisJoin && this.coordinator.needsInitialSetup()) {
                    this.setupPromptShownThisJoin = true;
                    this.client.setScreen(new BitCamSettingsScreen(this.client.screen, this.coordinator));
                }
            });
        }
    }

    private void onClientTick(Minecraft client) {
        while (this.toggleKey.consumeClick()) {
            this.toggleStreaming();
        }
        while (this.settingsKey.consumeClick()) {
            this.openSettings();
        }

        if (this.coordinator != null) {
            if (!this.coordinator.hasWelcome() && client.getConnection() != null && --this.helloRetryCooldown <= 0) {
                this.sendHello();
                this.helloRetryCooldown = 20;
            }
            this.coordinator.pruneRemoteFrames(10_000L);
        }
        this.billboardRenderer.tick();
    }

    private void sendHello() {
        if (this.coordinator != null) {
            ClientPlayNetworking.send(FabricBitCamControlPayload.fromSignalPacket(this.coordinator.createHelloPacket()));
        }
    }

    private void toggleStreaming() {
        if (this.coordinator == null) {
            this.sendMessage("BitCam is not connected to a compatible server.");
            return;
        }

        if (!this.coordinator.streamingEnabled() && this.coordinator.cameras().isEmpty()) {
            String statusMessage = this.coordinator.cameraStatusMessage();
            this.sendMessage(statusMessage.isBlank() ? "No webcams detected." : statusMessage);
            return;
        }

        this.coordinator.toggleStreaming();
        this.sendMessage("BitCam streaming " + (this.coordinator.streamingEnabled() ? "enabled" : "disabled"));
    }

    private void listCameras() {
        if (this.coordinator == null) {
            this.sendMessage("BitCam cameras are available after joining a world.");
            return;
        }

        List<CameraDeviceInfo> cameras = this.coordinator.cameras();
        if (cameras.isEmpty()) {
            String statusMessage = this.coordinator.cameraStatusMessage();
            this.sendMessage(statusMessage.isBlank() ? "No webcams detected." : statusMessage);
            return;
        }

        StringBuilder message = new StringBuilder("BitCam cameras:");
        for (int index = 0; index < cameras.size(); index++) {
            CameraDeviceInfo camera = cameras.get(index);
            message.append(' ').append(index).append('=').append(camera.name());
            if (camera.id().equals(this.coordinator.selectedCameraId())) {
                message.append(" [selected]");
            }
        }
        this.sendMessage(message.toString());
    }

    private void selectCamera(int index) {
        if (this.coordinator == null) {
            this.sendMessage("Join a world before selecting a camera.");
            return;
        }

        List<CameraDeviceInfo> cameras = this.coordinator.cameras();
        if (cameras.isEmpty()) {
            String statusMessage = this.coordinator.cameraStatusMessage();
            this.sendMessage(statusMessage.isBlank() ? "No webcams detected." : statusMessage);
            return;
        }

        if (index < 0 || index >= cameras.size()) {
            this.sendMessage("Camera index out of range.");
            return;
        }

        CameraDeviceInfo selected = cameras.get(index);
        this.coordinator.selectCamera(selected);
        this.sendMessage("BitCam camera set to " + selected.name());
    }

    private void openSettings() {
        if (this.coordinator == null) {
            this.sendMessage("Join a world before opening BitCam settings.");
            return;
        }

        this.client.setScreen(new BitCamSettingsScreen(this.client.screen, this.coordinator));
    }

    private void sendMessage(String message) {
        if (this.client.player != null) {
            this.client.player.displayClientMessage(Component.literal(message), false);
        } else {
            this.logger.info(message);
        }
    }

    private void closeCoordinator() {
        if (this.coordinator != null) {
            this.coordinator.close();
            this.coordinator = null;
        }
        this.helloRetryCooldown = 0;
    }
}
