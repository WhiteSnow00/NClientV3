package com.maxwai.nclientv3.bypass;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Socket;

public final class BypassManager {
    private static final BypassManager INSTANCE = new BypassManager();

    private final BypassStateStore stateStore = new BypassStateStore();
    @Nullable
    private Context appContext;
    @Nullable
    private volatile BypassDirectSocketProtector directSocketProtector;

    private BypassManager() {
    }

    @NonNull
    public static BypassManager getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize(@NonNull Context context) {
        if (appContext != null) return;
        appContext = context.getApplicationContext();
        stateStore.initialize(new BypassPreferences(appContext));
        BypassState restoredState = stateStore.get();
        if (restoredState.getMode() != BypassMode.DIRECT || restoredState.getStage() != BypassStage.IDLE) {
            stateStore.update(BypassState.idle());
        }
    }

    @NonNull
    public BypassStateStore getStateStore() {
        return stateStore;
    }

    @NonNull
    public BypassState getState() {
        return stateStore.get();
    }

    public void updateState(@NonNull BypassMode mode, @NonNull BypassStage stage, @Nullable String host) {
        stateStore.update(new BypassState(mode, stage, host, System.currentTimeMillis()));
    }

    public void startVpn() {
        Context context = requireContext();
        updateState(BypassMode.VPN, BypassStage.STARTING, null);
        BypassServiceManager.startVpn(context);
    }

    public void startProxy() {
        Context context = requireContext();
        updateState(BypassMode.PROXY, BypassStage.STARTING, null);
        BypassServiceManager.startProxy(context);
    }

    public void stop() {
        Context context = requireContext();
        BypassMode activeMode = getState().getMode();
        updateState(BypassMode.DIRECT, BypassStage.STOPPING, null);
        BypassServiceManager.stop(context, activeMode);
    }

    public void setDirectSocketProtector(@Nullable BypassDirectSocketProtector directSocketProtector) {
        this.directSocketProtector = directSocketProtector;
    }

    public boolean protectSocket(@NonNull Socket socket) {
        BypassDirectSocketProtector protector = directSocketProtector;
        return protector != null && protector.protectSocket(socket);
    }

    @NonNull
    private Context requireContext() {
        Context context = appContext;
        if (context == null) {
            throw new IllegalStateException("BypassManager is not initialized");
        }
        return context;
    }
}
