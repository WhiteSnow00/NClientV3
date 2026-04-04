package com.maxwai.nclientv3.bypass;

public final class BypassRuntimeStatus {
    private static volatile boolean vpnServiceCreated;
    private static volatile boolean vpnStarting;
    private static volatile boolean vpnReady;
    private static volatile boolean proxyServiceCreated;
    private static volatile boolean proxyStarting;
    private static volatile boolean proxyReady;

    private BypassRuntimeStatus() {
    }

    public static void markVpnServiceCreated() {
        vpnServiceCreated = true;
    }

    public static void markVpnStarting() {
        vpnServiceCreated = true;
        vpnStarting = true;
        vpnReady = false;
    }

    public static void markVpnReady() {
        vpnServiceCreated = true;
        vpnStarting = false;
        vpnReady = true;
    }

    public static void markVpnStopped() {
        vpnStarting = false;
        vpnReady = false;
        vpnServiceCreated = false;
    }

    public static void markProxyServiceCreated() {
        proxyServiceCreated = true;
    }

    public static void markProxyStarting() {
        proxyServiceCreated = true;
        proxyStarting = true;
        proxyReady = false;
    }

    public static void markProxyReady() {
        proxyServiceCreated = true;
        proxyStarting = false;
        proxyReady = true;
    }

    public static void markProxyStopped() {
        proxyStarting = false;
        proxyReady = false;
        proxyServiceCreated = false;
    }

    public static boolean isVpnStarting() {
        return vpnServiceCreated && vpnStarting;
    }

    public static boolean isVpnReady() {
        return vpnServiceCreated && vpnReady;
    }

    public static boolean isVpnServiceCreated() {
        return vpnServiceCreated;
    }

    public static boolean isProxyStarting() {
        return proxyServiceCreated && proxyStarting;
    }

    public static boolean isProxyReady() {
        return proxyServiceCreated && proxyReady;
    }

    public static boolean isProxyServiceCreated() {
        return proxyServiceCreated;
    }
}
