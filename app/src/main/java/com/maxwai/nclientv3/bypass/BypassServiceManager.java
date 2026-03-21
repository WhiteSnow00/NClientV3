package com.maxwai.nclientv3.bypass;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

public final class BypassServiceManager {
    private BypassServiceManager() {
    }

    public static void startVpn(@NonNull Context context) {
        Intent intent = new Intent(context, BypassVpnService.class);
        intent.setAction(BypassActions.ACTION_START_VPN);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void startProxy(@NonNull Context context) {
        Intent intent = new Intent(context, BypassProxyService.class);
        intent.setAction(BypassActions.ACTION_START_PROXY);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(@NonNull Context context) {
        BypassState state = BypassManager.getInstance().getState();
        Class<?> serviceClass = state.getMode() == BypassMode.PROXY ? BypassProxyService.class : BypassVpnService.class;
        String action = state.getMode() == BypassMode.PROXY ? BypassActions.ACTION_STOP_PROXY : BypassActions.ACTION_STOP_VPN;
        Intent intent = new Intent(context, serviceClass);
        intent.setAction(action);
        ContextCompat.startForegroundService(context, intent);
    }
}
