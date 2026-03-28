package dev.nexbit.bitcam.clientcommon;

public record CameraDeviceInfo(String id, String name) {
    public CameraDeviceInfo(String name) {
        this(name, name);
    }
}
