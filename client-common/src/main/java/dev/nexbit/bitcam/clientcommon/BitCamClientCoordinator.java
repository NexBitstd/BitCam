package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.common.PlatformAccess;
import dev.nexbit.bitcam.protocol.BitCamProtocol;
import dev.nexbit.bitcam.protocol.signal.ClientHelloSignalPacket;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import dev.nexbit.bitcam.protocol.signal.BitCamStreamQualityProfile;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class BitCamClientCoordinator implements AutoCloseable {
    private final PlatformAccess platform;
    private final Supplier<UUID> playerIdSupplier;
    private final BitCamClientConfig config;
    private final BitCamHelloRequester helloRequester;
    private final RemoteFrameStore frameStore;
    private final LocalPreviewStore previewStore = new LocalPreviewStore();
    private final CameraCaptureController cameraCapture = new CameraCaptureController();

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bitcam-camera");
        thread.setDaemon(true);
        return thread;
    });

    private BitCamUdpClient udpClient;
    private String lastCameraErrorMessage = "";
    private volatile boolean streamingEnabled;
    private volatile boolean cameraStarting;
    private ServerWelcomeSignalPacket welcome;
    private List<BitCamStreamQualityProfile> availableQualityProfiles = List.of();
    private String selectedQualityProfileId = "";

    public BitCamClientCoordinator(PlatformAccess platform, Supplier<UUID> playerIdSupplier, BitCamHelloRequester helloRequester) {
        this.platform = platform;
        this.playerIdSupplier = playerIdSupplier;
        this.helloRequester = helloRequester;
        this.config = BitCamClientConfig.load(platform.configDirectory());
        this.frameStore = new RemoteFrameStore(platform.logger());
    }

    public ClientHelloSignalPacket createHelloPacket() {
        return new ClientHelloSignalPacket(BitCamProtocol.PROTOCOL_VERSION, this.config.preferredQualityProfileId());
    }

    public void handleWelcome(ServerWelcomeSignalPacket welcome) {
        if (welcome.protocolVersion() != BitCamProtocol.PROTOCOL_VERSION) {
            this.platform.logger().warn(
                "Ignoring BitCam welcome with unsupported protocol version " + welcome.protocolVersion()
            );
            return;
        }

        boolean firstWelcome = this.welcome == null;
        boolean shouldRestartStreaming = this.streamingEnabled;
        this.welcome = welcome;
        this.availableQualityProfiles = welcome.availableQualityProfiles();
        this.selectedQualityProfileId = welcome.selectedQualityProfileId();
        if (this.udpClient != null) {
            this.udpClient.close();
        }

        this.udpClient = new BitCamUdpClient(
            this.platform,
            this.frameStore,
            this.cameraCapture::requestKeyframe,
            this.cameraCapture::reportNetworkLoss,
            this.config.fecEnabled()
        );
        this.udpClient.connect(welcome);
        this.platform.logger().info("BitCam client received UDP endpoint " + welcome.udpHost() + ":" + welcome.udpPort() + " and is waiting for session acceptance");

        if ((shouldRestartStreaming || firstWelcome) && this.hasCameraSelection()) {
            this.startStreaming();
        }
    }

    public void toggleStreaming() {
        if (this.cameraStarting) {
            return;
        }
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

    public boolean isCameraStarting() {
        return this.cameraStarting;
    }

    public boolean isCameraInitializing() {
        return CameraCatalog.isInitializing();
    }

    public boolean remotePreviewEnabled() {
        return this.config.remotePreviewEnabled();
    }

    public void toggleRemotePreview() {
        this.config.remotePreviewEnabled(!this.config.remotePreviewEnabled());
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

    public List<BitCamStreamQualityProfile> availableQualityProfiles() {
        return this.availableQualityProfiles;
    }

    public String selectedQualityProfileId() {
        return this.selectedQualityProfileId.isBlank() ? this.config.preferredQualityProfileId() : this.selectedQualityProfileId;
    }

    public void selectQualityProfile(String qualityProfileId) {
        String normalized = qualityProfileId == null ? "" : qualityProfileId.trim();
        this.config.preferredQualityProfileId(normalized);
        if (this.welcome != null && this.helloRequester != null) {
            this.helloRequester.requestHello();
        }
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

        this.cameraStarting = true;
        ServerWelcomeSignalPacket welcomeSnapshot = this.welcome;
        BitCamUdpClient udpClientSnapshot = this.udpClient;
        this.cameraExecutor.submit(() -> {
            try {
                this.awaitCameraBackendReady();
                this.cameraCapture.start(
                    this.config.preferredCameraName(),
                    this.config.preferredCaptureMode(),
                    welcomeSnapshot.width(),
                    welcomeSnapshot.height(),
                    welcomeSnapshot.fps(),
                    welcomeSnapshot.quality(),
                    frame -> udpClientSnapshot.sendFrame(this.playerIdSupplier.get(), frame, this.config.bubbleStyle()),
                    this.previewStore::accept,
                    exception -> this.handleAsyncCameraFailure(udpClientSnapshot, exception)
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
            } finally {
                this.cameraStarting = false;
            }
        });
    }

    private void handleAsyncCameraFailure(BitCamUdpClient udpClientSnapshot, Throwable exception) {
        this.streamingEnabled = false;
        this.previewStore.clear();
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
            ? "Camera streaming stopped unexpectedly."
            : exception.getMessage();
        this.lastCameraErrorMessage = message;
        try {
            udpClientSnapshot.sendStop(this.playerIdSupplier.get());
        } catch (RuntimeException ignored) {
            // Best-effort: the UDP client may already be closing while the capture thread unwinds.
        }
        this.platform.logger().warn("BitCam camera streaming stopped unexpectedly: " + message);
        this.platform.logger().error("BitCam async camera capture failure", exception);
    }

    private void awaitCameraBackendReady() {
        while (CameraCatalog.isInitializing()) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the camera backend.", exception);
            }
        }
    }

    private void stopStreaming() {
        this.cameraExecutor.submit(() -> {
            this.cameraCapture.stop();
            this.previewStore.clear();
            if (this.udpClient != null) {
                this.udpClient.sendStop(this.playerIdSupplier.get());
            }
        });
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
        this.cameraExecutor.shutdownNow();
        this.cameraCapture.close();
        this.previewStore.clear();
        this.frameStore.close();
        this.availableQualityProfiles = List.of();
        this.selectedQualityProfileId = "";
        if (this.udpClient != null) {
            this.udpClient.close();
        }
    }
}
