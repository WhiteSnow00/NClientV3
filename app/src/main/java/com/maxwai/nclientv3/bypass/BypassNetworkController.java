package com.maxwai.nclientv3.bypass;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.settings.Global;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Request;
import okhttp3.Response;

public final class BypassNetworkController implements BypassStateStore.Listener {
    private static final BypassNetworkController INSTANCE = new BypassNetworkController();

    private final ConcurrentHashMap<String, HostAccessRecord> hostAccess = new ConcurrentHashMap<>();
    @Nullable
    private Context appContext;

    private BypassNetworkController() {
    }

    @NonNull
    public static BypassNetworkController getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize(@NonNull Context context) {
        if (appContext != null) return;
        appContext = context.getApplicationContext();
        BypassManager.getInstance().getStateStore().addListener(this);
    }

    public void onRequestStarted(@NonNull Request request) {
        recordFor(request.url().host()).markStarted();
    }

    public void onRequestFinished(@NonNull Request request, @Nullable Response response, @Nullable IOException exception) {
        HostAccessRecord record = recordFor(request.url().host());
        record.markFinished(response, exception);
    }

    @Nullable
    public HostAccessSnapshot snapshotForHost(@Nullable String host) {
        if (host == null) return null;
        HostAccessRecord record = hostAccess.get(normalizeHost(host));
        return record == null ? null : record.snapshot();
    }

    @Override
    public void onBypassStateChanged(@NonNull BypassState state) {
        Global.evictHttpConnections();
    }

    @NonNull
    private HostAccessRecord recordFor(@Nullable String host) {
        return hostAccess.computeIfAbsent(normalizeHost(host), ignored -> new HostAccessRecord());
    }

    @NonNull
    private String normalizeHost(@Nullable String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    public static final class HostAccessSnapshot {
        public final long startedAt;
        public final long finishedAt;
        public final long successAt;
        public final long failureAt;
        public final int lastStatusCode;
        @Nullable
        public final String lastFailureClass;

        private HostAccessSnapshot(long startedAt, long finishedAt, long successAt, long failureAt, int lastStatusCode, @Nullable String lastFailureClass) {
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.successAt = successAt;
            this.failureAt = failureAt;
            this.lastStatusCode = lastStatusCode;
            this.lastFailureClass = lastFailureClass;
        }
    }

    private static final class HostAccessRecord {
        private volatile long startedAt;
        private volatile long finishedAt;
        private volatile long successAt;
        private volatile long failureAt;
        private volatile int lastStatusCode;
        @Nullable
        private volatile String lastFailureClass;

        void markStarted() {
            startedAt = System.currentTimeMillis();
        }

        void markFinished(@Nullable Response response, @Nullable IOException exception) {
            finishedAt = System.currentTimeMillis();
            if (response != null) {
                lastStatusCode = response.code();
                if (response.isSuccessful()) {
                    successAt = finishedAt;
                    lastFailureClass = null;
                }
            }
            if (exception != null) {
                failureAt = finishedAt;
                lastFailureClass = exception.getClass().getName();
            }
        }

        @NonNull
        HostAccessSnapshot snapshot() {
            return new HostAccessSnapshot(
                startedAt,
                finishedAt,
                successAt,
                failureAt,
                lastStatusCode,
                lastFailureClass
            );
        }
    }
}
