package com.maxwai.nclientv3.bypass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class BypassState {
    private final BypassMode mode;
    private final BypassStage stage;
    @Nullable
    private final String activeHost;
    private final long updatedAt;

    public BypassState(@NonNull BypassMode mode, @NonNull BypassStage stage, @Nullable String activeHost, long updatedAt) {
        this.mode = Objects.requireNonNull(mode);
        this.stage = Objects.requireNonNull(stage);
        this.activeHost = activeHost;
        this.updatedAt = updatedAt;
    }

    @NonNull
    public static BypassState idle() {
        return new BypassState(BypassMode.DIRECT, BypassStage.IDLE, null, System.currentTimeMillis());
    }

    @NonNull
    public BypassMode getMode() {
        return mode;
    }

    @NonNull
    public BypassStage getStage() {
        return stage;
    }

    @Nullable
    public String getActiveHost() {
        return activeHost;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    @NonNull
    public BypassState copy(@NonNull BypassMode newMode, @NonNull BypassStage newStage, @Nullable String newHost) {
        return new BypassState(newMode, newStage, newHost, System.currentTimeMillis());
    }
}
