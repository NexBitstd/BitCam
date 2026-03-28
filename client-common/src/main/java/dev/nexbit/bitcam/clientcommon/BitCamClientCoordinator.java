package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.common.PlatformAccess;
import dev.nexbit.bitcam.protocol.BitCamProtocol;
import dev.nexbit.bitcam.protocol.signal.ClientHelloSignalPacket;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class BitCamClientCoordinator implements AutoCloseable {
    private final PlatformAccess platform;
    private final Supplier<UUID> playerIdSupplier;
    private final BitCamClientConfig config;
    private final RemoteFrameStore frameStore = new RemoteFrameStore();
    private final LocalPreviewStore previewStore = new LocalPreviewStore();
    private final CameraCaptureController cameraCapture = new CameraCaptureController();

    private BitCamUdpClient udpClient;
    private String lastCameraErrorMessage = "";
    private boolean streamingEnabled;
    private ServerWelcomeSignalPacket welcome;

    public BitCamClientCoordinator(PlatformAccess platform, Supplier<UUID> playerIdSupplier) {
        this.platform = platform;
        this.playerIdSupplier = playerIdSupplier;
        this.config = BitCamClientConfig.load(platform.configDirectory());
    }

    public ClientHelloSignalPacket createHelloPacket() {
        return new ClientHelloSignalPacket(BitCamProtocol.PROTOCOL_VERSION);
    }

    public void handleWelcome(ServerWelcomeSignalPacket welcome) {
        if (welcome.protocolVersion() != BitCamProtocol.PROTOCOL_VERSION) {
            this.platform.logger().warn(
                "Ignoring BitCam welcome with unsupported protocol version " + welcome.protocolVersion()
            );
            return;
        }

        this.welcome = welcome;
        if (this.udpClient != null) {
            this.udpClient.close();
        }

        this.udpClient = new BitCamUdpClient(this.platform, this.frameStore);
        this.udpClient.connect(welcome);
        this.platform.logger().info("BitCam client received UDP endpoint " + welcome.udpHost() + ":" + welcome.udpPort() + " and is waiting for session acceptance");

        if (this.hasCameraSelection()) {
            this.startStreaming();
        }
    }

    public void toggleStreaming() {
        if (this.streamingEnabled) {
            this.streamingEnabled = false;
            this.stopStreaming();
            return;
        }

        this.startStreaming();
    }

    public boolean streamingEnabled() {
        return this.streamingEnabled;
    }

    public boolean hasWelcome() {
        return this.welcome != null;
    }

    public RemoteFrameStore frameStore() {
        return this.frameStore;
    }

    public LocalPreviewStore previewStore() {
        return this.previewStore;
    }

    public String selectedCameraId() {
        return this.config.preferredCameraName();
    }

    public CameraCaptureMode selectedCaptureMode() {
        return this.config.preferredCaptureMode();
    }

    public BitCamBubbleStyle bubbleStyle() {
        return this.config.bubbleStyle();
    }

    public boolean needsInitialSetup() {
        return !this.config.setupCompleted();
    }

    public String cameraStatusMessage() {
        return this.lastCameraErrorMessage.isBlank() ? CameraCatalog.statusMessage() : this.lastCameraErrorMessage;
    }

    public void updateBubbleStyle(BitCamBubbleStyle bubbleStyle) {
        this.config.bubbleStyle(bubbleStyle);
    }

    public List<CameraDeviceInfo> cameras() {
        List<CameraDeviceInfo> cameras = CameraCatalog.listDevices();
        if (!cameras.isEmpty() || CameraCatalog.statusMessage().isBlank()) {
            this.lastCameraErrorMessage = "";
        }
        return cameras;
    }

    public List<CameraDeviceInfo> refreshCameras() {
        List<CameraDeviceInfo> cameras = CameraCatalog.refreshDevices();
        if (!cameras.isEmpty() || CameraCatalog.statusMessage().isBlank()) {
            this.lastCameraErrorMessage = "";
        }
        return cameras;
    }

    public List<CameraCaptureMode> cameraModes(String cameraId) {
        return CameraCatalog.listModes(cameraId);
    }

    public void selectCamera(CameraDeviceInfo camera) {
        this.config.preferredCameraName(camera == null ? "" : camera.id());
        this.refreshCameraStreamingState();
    }

    public void selectCaptureMode(CameraCaptureMode captureMode) {
        this.config.preferredCaptureMode(captureMode);
        this.refreshCameraStreamingState();
    }

    public boolean isPlayerHidden(UUID playerId) {
        return this.config.isPlayerHidden(playerId);
    }

    public void setPlayerHidden(UUID playerId, boolean hidden) {
        this.config.setPlayerHidden(playerId, hidden);
    }

    public void pruneRemoteFrames(long maxAgeMillis) {
        this.frameStore.pruneExpired(maxAgeMillis);
    }

    private void startStreaming() {
        if (this.welcome == null || this.udpClient == null) {
            return;
        }

        try {
            this.cameraCapture.start(
                this.config.preferredCameraName(),
                this.config.preferredCaptureMode(),
                this.welcome.width(),
                this.welcome.height(),
                this.welcome.fps(),
                this.welcome.quality(),
                frame -> {
                    this.previewStore.accept(frame);
                    this.udpClient.sendFrame(this.playerIdSupplier.get(), frame, this.config.bubbleStyle());
                }
            );
            this.lastCameraErrorMessage = "";
            this.streamingEnabled = true;
            this.config.setupCompleted(true);
        } catch (RuntimeException exception) {
            this.streamingEnabled = false;
            this.previewStore.clear();
            this.lastCameraErrorMessage = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Failed to start camera streaming."
                : exception.getMessage();
            this.platform.logger().warn("Failed to start BitCam camera streaming: " + this.lastCameraErrorMessage);
            this.platform.logger().error("BitCam camera start failure", exception);
        }
    }

    private void stopStreaming() {
        this.cameraCapture.stop();
        this.previewStore.clear();
        if (this.udpClient != null) {
            this.udpClient.sendStop(this.playerIdSupplier.get());
        }
    }

    private void refreshCameraStreamingState() {
        if (!this.hasCameraSelection()) {
            if (this.streamingEnabled) {
                this.stopStreaming();
                this.streamingEnabled = false;
            }
            return;
        }

        if (this.welcome != null && this.udpClient != null) {
            this.startStreaming();
        }
    }

    private boolean hasCameraSelection() {
        return !this.config.preferredCameraName().isBlank();
    }

    @Override
    public void close() {
        this.cameraCapture.close();
        this.previewStore.clear();
        if (this.udpClient != null) {
            this.udpClient.close();
        }
    }
}
