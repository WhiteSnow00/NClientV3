package com.maxwai.nclientv3.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

public final class BypassPreferences {
    private static final String PREFS_NAME = "InternalBypass";
    private static final String KEY_MODE = "mode";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_HOST = "host";
    private static final String KEY_UPDATED_AT = "updated_at";
    private static final String KEY_PROXY_IP = "proxy_ip";
    private static final String KEY_PROXY_PORT = "proxy_port";
    private static final String KEY_DNS_IP = "dns_ip";
    private static final String KEY_IPV6_ENABLED = "ipv6_enabled";

    private final SharedPreferences sharedPreferences;

    public BypassPreferences(@NonNull Context context) {
        sharedPreferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    public BypassState readState() {
        String mode = sharedPreferences.getString(KEY_MODE, BypassMode.DIRECT.name());
        String stage = sharedPreferences.getString(KEY_STAGE, BypassStage.IDLE.name());
        String host = sharedPreferences.getString(KEY_HOST, null);
        long updatedAt = sharedPreferences.getLong(KEY_UPDATED_AT, System.currentTimeMillis());
        return new BypassState(
            BypassMode.fromStorage(mode),
            BypassStage.fromStorage(stage),
            host,
            updatedAt
        );
    }

    public void writeState(@NonNull BypassState state) {
        sharedPreferences.edit()
            .putString(KEY_MODE, state.getMode().name())
            .putString(KEY_STAGE, state.getStage().name())
            .putString(KEY_HOST, state.getActiveHost())
            .putLong(KEY_UPDATED_AT, state.getUpdatedAt())
            .apply();
    }

    @NonNull
    public String getProxyIp() {
        String proxyIp = sharedPreferences.getString(KEY_PROXY_IP, BypassProxyConfig.DEFAULT_PROXY_IP);
        return proxyIp == null || proxyIp.trim().isEmpty() ? BypassProxyConfig.DEFAULT_PROXY_IP : proxyIp.trim();
    }

    public int getProxyPort() {
        String proxyPort = sharedPreferences.getString(KEY_PROXY_PORT, String.valueOf(BypassProxyConfig.DEFAULT_PROXY_PORT));
        if (proxyPort == null) return BypassProxyConfig.DEFAULT_PROXY_PORT;
        try {
            int parsed = Integer.parseInt(proxyPort.trim());
            return parsed > 0 ? parsed : BypassProxyConfig.DEFAULT_PROXY_PORT;
        } catch (NumberFormatException ignore) {
            return BypassProxyConfig.DEFAULT_PROXY_PORT;
        }
    }

    @NonNull
    public String getDnsIp() {
        String dnsIp = sharedPreferences.getString(KEY_DNS_IP, BypassProxyConfig.DEFAULT_DNS_IP);
        return dnsIp == null || dnsIp.trim().isEmpty() ? BypassProxyConfig.DEFAULT_DNS_IP : dnsIp.trim();
    }

    public boolean isIpv6Enabled() {
        return sharedPreferences.getBoolean(KEY_IPV6_ENABLED, false);
    }
}
