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
}
