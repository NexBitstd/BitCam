package dev.nexbit.bitcam.common;

public enum BitCamLoader {
    FABRIC("Fabric"),
    NEOFORGE("NeoForge"),
    PAPER("Paper");

    private final String displayName;

    BitCamLoader(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
