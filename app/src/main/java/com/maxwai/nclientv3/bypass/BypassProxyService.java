package com.maxwai.nclientv3.bypass;

import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleService;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.utility.LogUtility;

public class BypassProxyService extends LifecycleService {
    private static final int NOTIFICATION_ID = 4011;
    private static final long PROXY_READY_TIMEOUT_MS = 5000L;

    private final Object serviceLock = new Object();
    private final BypassProxyRuntime proxyRuntime = new BypassProxyRuntime();
    @Nullable
    private Thread startupThread;
    private volatile boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        BypassNotificationHelper.ensureChannels(this);
        BypassManager.getInstance().initialize(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        String action = intent == null ? null : intent.getAction();
        if (BypassActions.ACTION_START_PROXY.equals(action)) {
            handleStart();
            return START_STICKY;
        }
        if (BypassActions.ACTION_STOP_PROXY.equals(action)) {
            new Thread(this::stopInternal, "NClientBypassProxyStop").start();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void handleStart() {
        synchronized (serviceLock) {
            if (proxyRuntime.isRunning() || startupThread != null) {
                return;
            }
            stopping = false;
        }

        startForegroundCompat(
            BypassNotificationHelper.buildProxyNotification(
                this,
                getString(R.string.bypass_notification_preparing_proxy)
            )
        );
        BypassManager.getInstance().updateState(BypassMode.PROXY, BypassStage.STARTING, null);

        BypassProxyConfig config = BypassProxyConfig.fromContext(this);
        if (!proxyRuntime.start(config, null, this::onProxyExit)) {
            return;
        }

        Thread thread = new Thread(() -> waitForProxy(config), "NClientBypassProxyStart");
        synchronized (serviceLock) {
            startupThread = thread;
        }
        thread.start();
    }

    private void waitForProxy(@NonNull BypassProxyConfig config) {
        try {
            boolean ready = proxyRuntime.waitUntilReachable(config.getProxyIp(), config.getProxyPort(), PROXY_READY_TIMEOUT_MS);
            if (!ready) {
                failAndStop("Local proxy did not become reachable in time.");
                return;
            }
            updateNotification(getString(R.string.bypass_notification_proxy_active));
            BypassManager.getInstance().updateState(BypassMode.PROXY, BypassStage.RUNNING, null);
        } finally {
            synchronized (serviceLock) {
                if (startupThread == Thread.currentThread()) {
                    startupThread = null;
                }
            }
        }
    }

    private void onProxyExit(int exitCode, boolean stopRequested) {
        if (stopRequested) {
            return;
        }
        LogUtility.w("Local proxy exited unexpectedly. Exit code=", exitCode);
        BypassManager.getInstance().updateState(BypassMode.PROXY, BypassStage.FAILED, null);
        new Thread(() -> stopInternal(false), "NClientBypassProxyExit").start();
    }

    private void failAndStop(String message) {
        if (stopping) return;
        LogUtility.w(message);
        BypassManager.getInstance().updateState(BypassMode.PROXY, BypassStage.FAILED, null);
        new Thread(() -> stopInternal(false), "NClientBypassProxyFail").start();
    }

    private void stopInternal() {
        stopInternal(true);
    }

    private void stopInternal(boolean markIdle) {
        synchronized (serviceLock) {
            if (stopping) {
                return;
            }
            stopping = true;
        }

        proxyRuntime.stop(1000L);
        stopActiveForeground();

        if (markIdle) {
            BypassManager.getInstance().updateState(BypassMode.DIRECT, BypassStage.IDLE, null);
        }
        startupThread = null;
        stopSelf();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.notify(
            NOTIFICATION_ID,
            BypassNotificationHelper.buildProxyNotification(this, text)
        );
    }

    private void startForegroundCompat(android.app.Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopActiveForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }
}
