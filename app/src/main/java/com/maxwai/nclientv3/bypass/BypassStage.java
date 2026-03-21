package com.maxwai.nclientv3.bypass;

import androidx.annotation.NonNull;

public enum BypassStage {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED;

    @NonNull
    public static BypassStage fromStorage(String value) {
        if (value == null) return IDLE;
        for (BypassStage stage : values()) {
            if (stage.name().equalsIgnoreCase(value)) {
                return stage;
            }
        }
        return IDLE;
    }
}
