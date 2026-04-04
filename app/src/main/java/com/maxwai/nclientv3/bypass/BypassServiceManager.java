package com.maxwai.nclientv3.bypass;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.maxwai.nclientv3.utility.LogUtility;

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

    public static void stop(@NonNull Context context, @NonNull BypassMode requestedMode) {
        boolean vpnActive = BypassRuntimeStatus.isVpnServiceCreated() || BypassRuntimeStatus.isVpnStarting() || BypassRuntimeStatus.isVpnReady();
        boolean proxyActive = BypassRuntimeStatus.isProxyServiceCreated() || BypassRuntimeStatus.isProxyStarting() || BypassRuntimeStatus.isProxyReady();
        BypassMode targetMode = resolveTargetMode(requestedMode, vpnActive, proxyActive);
        if (targetMode == BypassMode.DIRECT) {
            return;
        }

        Class<?> serviceClass = targetMode == BypassMode.PROXY ? BypassProxyService.class : BypassVpnService.class;
        String action = targetMode == BypassMode.PROXY ? BypassActions.ACTION_STOP_PROXY : BypassActions.ACTION_STOP_VPN;
        Intent intent = new Intent(context, serviceClass);
        intent.setAction(action);
        try {
            context.startService(intent);
        } catch (Throwable t) {
            LogUtility.w("Unable to dispatch bypass stop request", t);
            try {
                context.stopService(new Intent(context, serviceClass));
            } catch (Throwable stopThrowable) {
                LogUtility.w("Unable to stop bypass service directly", stopThrowable);
            }
        }
    }

    @NonNull
    private static BypassMode resolveTargetMode(@NonNull BypassMode requestedMode, boolean vpnActive, boolean proxyActive) {
        if (requestedMode == BypassMode.VPN && vpnActive) {
            return BypassMode.VPN;
        }
        if (requestedMode == BypassMode.PROXY && proxyActive) {
            return BypassMode.PROXY;
        }
        if (vpnActive) {
            return BypassMode.VPN;
        }
        if (proxyActive) {
            return BypassMode.PROXY;
        }
        return BypassMode.DIRECT;
    }
}
