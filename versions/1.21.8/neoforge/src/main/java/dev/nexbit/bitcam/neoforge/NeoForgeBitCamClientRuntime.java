package dev.nexbit.bitcam.neoforge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.blaze3d.platform.InputConstants;
import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
import dev.nexbit.bitcam.clientcommon.CameraDeviceInfo;
import dev.nexbit.bitcam.neoforge.client.ui.BitCamSettingsScreen;
import dev.nexbit.bitcam.neoforge.network.NeoForgeBitCamControlPayload;
import dev.nexbit.bitcam.neoforge.platform.NeoForgePlatformAccess;
import dev.nexbit.bitcam.neoforge.render.NeoForgeBitCamBillboardRenderer;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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
    private final NeoForgeBitCamBillboardRenderer billboardRenderer = new NeoForgeBitCamBillboardRenderer(
        () -> this.coordinator
    );

    private BitCamClientCoordinator coordinator;
    private int helloRetryCooldown;
    private boolean setupPromptShownThisJoin;

    public NeoForgeBitCamClientRuntime(String minecraftVersion) {
        this.platform = NeoForgePlatformAccess.createClient(minecraftVersion);
    }

    public void registerClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(NeoForgeBitCamControlPayload.TYPE, this::handleControlPacket);
    }

    public void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(this.toggleKey);
        event.register(this.settingsKey);
    }

    public void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        this.closeCoordinator();
        this.coordinator = new BitCamClientCoordinator(this.platform, () -> event.getPlayer().getUUID());
        this.sendHello();
        this.helloRetryCooldown = 20;
        this.setupPromptShownThisJoin = false;
    }

    public void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        this.closeCoordinator();
        this.billboardRenderer.close();
        this.setupPromptShownThisJoin = false;
    }

    public void onClientTick(ClientTickEvent.Post event) {
        while (this.toggleKey.consumeClick()) {
            this.toggleStreaming();
        }
        while (this.settingsKey.consumeClick()) {
            this.openSettings();
        }

        if (this.coordinator != null) {
            if (!this.coordinator.hasWelcome() && this.client.getConnection() != null && --this.helloRetryCooldown <= 0) {
                this.sendHello();
                this.helloRetryCooldown = 20;
            }
            this.coordinator.pruneRemoteFrames(10_000L);
        }
    }

    private void sendHello() {
        if (this.coordinator != null) {
            ClientPacketDistributor.sendToServer(NeoForgeBitCamControlPayload.fromSignalPacket(this.coordinator.createHelloPacket()));
        }
    }

    public void onRenderPlayer(RenderPlayerEvent.Post event) {
        this.billboardRenderer.render(event);
    }

    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("bitcam")
                .then(Commands.literal("toggle").executes(context -> {
                    this.toggleStreaming();
                    return 1;
                }))
                .then(Commands.literal("cameras").executes(context -> {
                    this.listCameras();
                    return 1;
                }))
                .then(Commands.literal("settings").executes(context -> {
                    this.openSettings();
                    return 1;
                }))
                .then(Commands.literal("camera")
                    .then(Commands.argument("index", IntegerArgumentType.integer(0)).executes(context -> {
                        this.selectCamera(IntegerArgumentType.getInteger(context, "index"));
                        return 1;
                    })))
        );
    }

    private void handleControlPacket(NeoForgeBitCamControlPayload payload, IPayloadContext context) {
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
            this.platform.logger().info(message);
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
