package dev.nexbit.bitcam.clientcommon.runtime;

import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;

public interface BitCamClientUiHost {
    void showMessage(String message);

    void openSettings(BitCamClientCoordinator coordinator);
}
