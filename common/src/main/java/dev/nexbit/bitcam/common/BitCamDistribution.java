package dev.nexbit.bitcam.common;

public enum BitCamDistribution {
    CLIENT("client"),
    SERVER("server");

    private final String displayName;

    BitCamDistribution(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
