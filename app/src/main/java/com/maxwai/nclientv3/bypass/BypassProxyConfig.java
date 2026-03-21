package com.maxwai.nclientv3.bypass;

import android.content.Context;

import androidx.annotation.NonNull;

public final class BypassProxyConfig {
    public static final String DEFAULT_PROXY_IP = "127.0.0.1";
    public static final int DEFAULT_PROXY_PORT = 1080;
    public static final String DEFAULT_DNS_IP = "8.8.8.8";

    private final String proxyIp;
    private final int proxyPort;
    private final String dnsIp;
    private final boolean ipv6Enabled;

    public BypassProxyConfig(@NonNull String proxyIp, int proxyPort, @NonNull String dnsIp, boolean ipv6Enabled) {
        this.proxyIp = proxyIp;
        this.proxyPort = proxyPort;
        this.dnsIp = dnsIp;
        this.ipv6Enabled = ipv6Enabled;
    }

    @NonNull
    public static BypassProxyConfig fromContext(@NonNull Context context) {
        BypassPreferences preferences = new BypassPreferences(context);
        return new BypassProxyConfig(
            preferences.getProxyIp(),
            preferences.getProxyPort(),
            preferences.getDnsIp(),
            preferences.isIpv6Enabled()
        );
    }

    @NonNull
    public String getProxyIp() {
        return proxyIp;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    @NonNull
    public String getDnsIp() {
        return dnsIp;
    }

    public boolean isIpv6Enabled() {
        return ipv6Enabled;
    }

    @NonNull
    public String[] toNativeArgs() {
        return new String[]{
            "ciadpi",
            "--ip", proxyIp,
            "--port", String.valueOf(proxyPort),
            "-Ku",
            "-a1",
            "-An",
            "-o1",
            "-At,r,s",
            "-d1"
        };
    }
}
