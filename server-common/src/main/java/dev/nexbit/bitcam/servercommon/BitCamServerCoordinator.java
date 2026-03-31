package dev.nexbit.bitcam.servercommon;

import dev.nexbit.bitcam.common.BitCamPlayerPermissionChecker;
import dev.nexbit.bitcam.common.PlatformAccess;
import dev.nexbit.bitcam.protocol.BitCamProtocol;
import dev.nexbit.bitcam.protocol.signal.ClientHelloSignalPacket;
import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
import dev.nexbit.bitcam.protocol.signal.BitCamStreamQualityProfile;
import dev.nexbit.bitcam.protocol.udp.VideoFrameUdpPacket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BitCamServerCoordinator implements AutoCloseable {
    private static final long SESSION_TIMEOUT_MS = 30_000L;

    private final PlatformAccess platform;
    private final BitCamServerConfig config;
    private final BitCamViewerResolver viewerResolver;
    private final BitCamPlayerPermissionChecker permissionChecker;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, BitCamSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, BitCamSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<UUID, BitCamStreamState> streamsByPlayer = new ConcurrentHashMap<>();
    private final BitCamUdpServer udpServer;

    public BitCamServerCoordinator(PlatformAccess platform, BitCamViewerResolver viewerResolver, BitCamPlayerPermissionChecker permissionChecker) {
        this.platform = platform;
        this.config = BitCamServerConfig.load(platform.configDirectory());
        this.viewerResolver = viewerResolver;
        this.permissionChecker = permissionChecker;
        this.udpServer = new BitCamUdpServer(platform, this.config, this);
    }

    public void start() {
        this.udpServer.start();
    }

    public Optional<ServerWelcomeSignalPacket> createWelcomePacket(UUID playerId, ClientHelloSignalPacket hello) {
        if (hello.protocolVersion() != BitCamProtocol.PROTOCOL_VERSION) {
            this.platform.logger().warn(
                "Rejected BitCam hello from "
                    + playerId
                    + " due to protocol mismatch: client="
                    + hello.protocolVersion()
                    + ", server="
                    + BitCamProtocol.PROTOCOL_VERSION
            );
            return Optional.empty();
        }

        this.removeSession(playerId);

        byte[] secret = new byte[BitCamProtocol.SECRET_LENGTH];
        this.random.nextBytes(secret);
        List<BitCamStreamQualityProfile> allowedProfiles = this.allowedProfilesFor(playerId);
        BitCamStreamQualityProfile selectedProfile = this.selectProfileFor(playerId, hello.requestedQualityProfileId(), allowedProfiles);

        BitCamSession session = new BitCamSession(playerId, UUID.randomUUID(), secret, true, true);
        this.sessionsByPlayer.put(playerId, session);
        this.sessionsById.put(session.sessionId(), session);

        return Optional.of(new ServerWelcomeSignalPacket(
            BitCamProtocol.PROTOCOL_VERSION,
            this.config.udpHost(),
            this.config.udpPort(),
            session.sessionId(),
            session.secret(),
            this.config.mtu(),
            selectedProfile.width(),
            selectedProfile.height(),
            selectedProfile.fps(),
            selectedProfile.quality(),
            selectedProfile.id(),
            allowedProfiles,
            this.config.radius()
        ));
    }

    public boolean hasSession(UUID playerId) {
        return this.sessionsByPlayer.containsKey(playerId);
    }

    public boolean hasActiveReceiver(UUID playerId) {
        BitCamSession session = this.sessionsByPlayer.get(playerId);
        return session != null && session.address() != null && session.receiveEnabled();
    }

    public int streamRadius() {
        return this.config.radius();
    }

    public void removeSession(UUID playerId) {
        BitCamSession removed = this.sessionsByPlayer.remove(playerId);
        if (removed != null) {
            this.sessionsById.remove(removed.sessionId(), removed);
        }
        this.streamsByPlayer.remove(playerId);
    }

    public BitCamServerDebugSnapshot debugSnapshot() {
        this.cleanupExpiredSessions();

        long now = System.currentTimeMillis();
        List<BitCamClientDebugSnapshot> clients = this.sessionsByPlayer.values()
            .stream()
            .sorted(Comparator.comparing(BitCamSession::playerId))
            .map(session -> {
                BitCamStreamState streamState = this.streamsByPlayer.get(session.playerId());
                BitCamStreamDebugSnapshot stream = streamState == null ? null : streamState.snapshot(now);
                String address = session.address() == null ? "pending" : session.address().toString();
                return new BitCamClientDebugSnapshot(
                    session.playerId(),
                    session.sessionId(),
                    session.sendEnabled(),
                    session.receiveEnabled(),
                    address,
                    Math.max(0L, now - session.lastSeen()),
                    stream
                );
            })
            .toList();

        int activeReceivers = (int) this.sessionsByPlayer.values()
            .stream()
            .filter(session -> session.address() != null && session.receiveEnabled())
            .count();

        return new BitCamServerDebugSnapshot(
            this.config.udpHost(),
            this.config.udpPort(),
            this.config.mtu(),
            this.config.width(),
            this.config.height(),
            this.config.fps(),
            this.config.quality(),
            this.config.radius(),
            clients.size(),
            activeReceivers,
            this.streamsByPlayer.size(),
            clients
        );
    }

    BitCamSession session(UUID sessionId) {
        return this.sessionsById.get(sessionId);
    }

    BitCamSession sessionByPlayer(UUID playerId) {
        return this.sessionsByPlayer.get(playerId);
    }

    void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        this.sessionsById.values().removeIf(session -> {
            if (session.lastSeen() + SESSION_TIMEOUT_MS >= now) {
                return false;
            }

            this.sessionsByPlayer.remove(session.playerId(), session);
            this.streamsByPlayer.remove(session.playerId());
            return true;
        });
    }

    void recordVideoFrame(VideoFrameUdpPacket packet, int viewerCount) {
        this.streamsByPlayer
            .computeIfAbsent(packet.streamerId(), ignored -> new BitCamStreamState())
            .update(packet, viewerCount);
    }

    void clearStreamState(UUID playerId) {
        this.streamsByPlayer.remove(playerId);
    }

    BitCamViewerResolver viewerResolver() {
        return this.viewerResolver;
    }

    BitCamServerConfig config() {
        return this.config;
    }

    boolean validateSecret(BitCamSession session, byte[] secret) {
        return Arrays.equals(session.secret(), secret);
    }

    private List<BitCamStreamQualityProfile> allowedProfilesFor(UUID playerId) {
        List<BitCamStreamQualityProfile> profiles = this.config.qualityPresets().stream()
            .filter(preset -> this.permissionChecker == null || this.permissionChecker.hasPermission(playerId, preset.permissionExpression()))
            .map(BitCamServerQualityPreset::profile)
            .toList();
        return profiles.isEmpty() ? List.of(this.config.defaultQualityProfile()) : profiles;
    }

    private BitCamStreamQualityProfile selectProfileFor(UUID playerId, String requestedProfileId, List<BitCamStreamQualityProfile> allowedProfiles) {
        String requestedId = requestedProfileId == null ? "" : requestedProfileId.trim();
        if (!requestedId.isBlank()) {
            for (BitCamStreamQualityProfile profile : allowedProfiles) {
                if (profile.id().equalsIgnoreCase(requestedId)) {
                    return profile;
                }
            }
        }

        for (BitCamStreamQualityProfile profile : allowedProfiles) {
            if (profile.id().equals(this.config.defaultQualityPresetId())) {
                return profile;
            }
        }

        return allowedProfiles.isEmpty() ? this.config.defaultQualityProfile() : allowedProfiles.getFirst();
    }

    @Override
    public void close() {
        this.udpServer.close();
    }
}
