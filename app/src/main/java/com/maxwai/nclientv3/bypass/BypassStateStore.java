package com.maxwai.nclientv3.bypass;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

public final class BypassStateStore {
    private final AtomicReference<BypassState> currentState = new AtomicReference<>(BypassState.idle());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile BypassPreferences preferences;

    public void initialize(@NonNull BypassPreferences newPreferences) {
        preferences = newPreferences;
        currentState.set(newPreferences.readState());
    }

    @NonNull
    public BypassState get() {
        return currentState.get();
    }

    @NonNull
    public BypassState update(@NonNull BypassState newState) {
        currentState.set(newState);
        BypassPreferences localPreferences = preferences;
        if (localPreferences != null) {
            localPreferences.writeState(newState);
        }
        for (Listener listener : listeners) {
            listener.onBypassStateChanged(newState);
        }
        return newState;
    }

    public void addListener(@NonNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    public interface Listener {
        void onBypassStateChanged(@NonNull BypassState state);
    }
}
