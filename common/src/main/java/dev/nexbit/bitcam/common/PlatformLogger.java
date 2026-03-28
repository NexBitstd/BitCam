package dev.nexbit.bitcam.common;

public interface PlatformLogger {
    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable throwable);
}
