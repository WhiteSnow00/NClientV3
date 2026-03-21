package com.maxwai.nclientv3.bypass;

import androidx.annotation.NonNull;

public enum BypassMode {
    DIRECT,
    VPN,
    PROXY;

    @NonNull
    public static BypassMode fromStorage(String value) {
        if (value == null) return DIRECT;
        for (BypassMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return DIRECT;
    }
}
