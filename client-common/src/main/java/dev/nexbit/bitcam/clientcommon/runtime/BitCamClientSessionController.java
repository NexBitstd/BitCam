package dev.nexbit.bitcam.clientcommon.runtime;

import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
import dev.nexbit.bitcam.clientcommon.BitCamHelloRequester;
import dev.nexbit.bitcam.clientcommon.CameraDeviceInfo;
import dev.nexbit.bitcam.common.ClientPlatformAccess;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class BitCamClientSessionController implements AutoCloseable {
    private static final int HELLO_RETRY_TICKS = 20;

    private final ClientPlatformAccess platform;
    private final Supplier<UUID> localPlayerIdSupplier;
    private final BitCamClientUiHost uiHost;
    private final BitCamHelloRequester helloRequester;

    private BitCamClientCoordinator coordinator;
    private int helloRetryCooldown;
    private boolean setupPromptShownThisJoin;

    public BitCamClientSessionController(
        ClientPlatformAccess platform,
        Supplier<UUID> localPlayerIdSupplier,
        BitCamClientUiHost uiHost,
        BitCamHelloRequester helloRequester
    ) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.localPlayerIdSupplier = Objects.requireNonNull(localPlayerIdSupplier, "localPlayerIdSupplier");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.helloRequester = Objects.requireNonNull(helloRequester, "helloRequester");
    }

    public void onJoin() {
        this.close();
        this.coordinator = new BitCamClientCoordinator(this.platform, this.localPlayerIdSupplier, this.helloRequester);
        this.helloRetryCooldown = HELLO_RETRY_TICKS;
        this.setupPromptShownThisJoin = false;
    }

    public void onDisconnect() {
        this.close();
    }

    public BitCamClientCoordinator coordinator() {
        return this.coordinator;
    }

    public boolean shouldRetryHello(boolean hasConnection) {
        if (this.coordinator == null || this.coordinator.hasWelcome() || !hasConnection) {
            return false;
        }

        if (--this.helloRetryCooldown > 0) {
            return false;
        }

        this.helloRetryCooldown = HELLO_RETRY_TICKS;
        return true;
    }

    public void pruneRemoteFrames(long frameTtlMillis) {
        if (this.coordinator != null) {
            this.coordinator.pruneRemoteFrames(frameTtlMillis);
        }
    }

    public void handleWelcome(ServerWelcomeSignalPacket welcome) {
        if (this.coordinator == null) {
            return;
        }

        this.coordinator.handleWelcome(welcome);
        this.uiHost.showMessage("BitCam received UDP endpoint " + welcome.udpHost() + ":" + welcome.udpPort());
        if (!this.setupPromptShownThisJoin && this.coordinator.needsInitialSetup()) {
            this.setupPromptShownThisJoin = true;
            this.uiHost.openSettings(this.coordinator);
        }
    }

    public void toggleStreaming() {
        if (this.coordinator == null) {
            this.uiHost.showMessage("BitCam is not connected to a compatible server.");
            return;
        }

        if (!this.coordinator.streamingEnabled() && this.coordinator.cameras().isEmpty()) {
            this.uiHost.showMessage(this.cameraUnavailableMessage());
            return;
        }

        this.coordinator.toggleStreaming();
        if (this.coordinator.isCameraStarting()) {
            this.uiHost.showMessage("BitCam camera is starting.");
            return;
        }
        this.uiHost.showMessage("BitCam streaming " + (this.coordinator.streamingEnabled() ? "enabled" : "disabled"));
    }

    public void listCameras() {
        if (this.coordinator == null) {
            this.uiHost.showMessage("BitCam cameras are available after joining a world.");
            return;
        }

        List<CameraDeviceInfo> cameras = this.coordinator.cameras();
        if (cameras.isEmpty()) {
            this.uiHost.showMessage(this.cameraUnavailableMessage());
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
        this.uiHost.showMessage(message.toString());
    }

    public void selectCamera(int index) {
        if (this.coordinator == null) {
            this.uiHost.showMessage("Join a world before selecting a camera.");
            return;
        }

        List<CameraDeviceInfo> cameras = this.coordinator.cameras();
        if (cameras.isEmpty()) {
            this.uiHost.showMessage(this.cameraUnavailableMessage());
            return;
        }

        if (index < 0 || index >= cameras.size()) {
            this.uiHost.showMessage("Camera index out of range.");
            return;
        }

        CameraDeviceInfo selected = cameras.get(index);
        this.coordinator.selectCamera(selected);
        this.uiHost.showMessage("BitCam camera set to " + selected.name());
    }

    public void openSettings() {
        if (this.coordinator == null) {
            this.uiHost.showMessage("Join a world before opening BitCam settings.");
            return;
        }

        this.uiHost.openSettings(this.coordinator);
    }

    private String cameraUnavailableMessage() {
        if (this.coordinator == null) {
            return "No webcams detected.";
        }

        if (this.coordinator.isCameraInitializing()) {
            return "BitCam is still starting the camera backend.";
        }

        String statusMessage = this.coordinator.cameraStatusMessage();
        return statusMessage.isBlank() ? "No webcams detected." : statusMessage;
    }

    @Override
    public void close() {
        if (this.coordinator != null) {
            this.coordinator.close();
            this.coordinator = null;
        }
        this.helloRetryCooldown = 0;
        this.setupPromptShownThisJoin = false;
    }
}
