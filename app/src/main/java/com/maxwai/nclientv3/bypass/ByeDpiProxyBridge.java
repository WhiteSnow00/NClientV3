package com.maxwai.nclientv3.bypass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class ByeDpiProxyBridge {
    static {
        System.loadLibrary("byedpi");
    }

    @Nullable
    private volatile BypassSocketProtector socketProtector;

    public void setSocketProtector(@Nullable BypassSocketProtector socketProtector) {
        this.socketProtector = socketProtector;
    }

    public int startProxy(@NonNull BypassProxyConfig config) {
        return jniStartProxy(config.toNativeArgs());
    }

    public int stopProxy() {
        return jniStopProxy();
    }

    public int forceClose() {
        return jniForceClose();
    }

    @SuppressWarnings("unused")
    boolean protectSocket(int fd) {
        BypassSocketProtector protector = socketProtector;
        return protector == null || protector.protectSocket(fd);
    }

    private native int jniStartProxy(String[] args);

    private native int jniStopProxy();

    private native int jniForceClose();
}
