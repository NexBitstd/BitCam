package dev.nexbit.bitcam.servercommon;

import java.net.SocketAddress;
import java.util.UUID;

final class BitCamSession {
    private final UUID playerId;
    private final UUID sessionId;
    private final byte[] secret;
    private volatile boolean sendEnabled;
    private volatile boolean receiveEnabled;
    private volatile SocketAddress address;
    private volatile long lastSeen;

    BitCamSession(UUID playerId, UUID sessionId, byte[] secret, boolean sendEnabled, boolean receiveEnabled) {
        this.playerId = playerId;
        this.sessionId = sessionId;
        this.secret = secret.clone();
        this.sendEnabled = sendEnabled;
        this.receiveEnabled = receiveEnabled;
        this.lastSeen = System.currentTimeMillis();
    }

    public UUID playerId() {
        return this.playerId;
    }

    public UUID sessionId() {
        return this.sessionId;
    }

    public byte[] secret() {
        return this.secret.clone();
    }

    public boolean sendEnabled() {
        return this.sendEnabled;
    }

    public boolean receiveEnabled() {
        return this.receiveEnabled;
    }

    public SocketAddress address() {
        return this.address;
    }

    public long lastSeen() {
        return this.lastSeen;
    }

    public void accept(SocketAddress address, boolean sendEnabled, boolean receiveEnabled) {
        this.address = address;
        this.sendEnabled = sendEnabled;
        this.receiveEnabled = receiveEnabled;
        this.touch();
    }

    public void touch() {
        this.lastSeen = System.currentTimeMillis();
    }
}
