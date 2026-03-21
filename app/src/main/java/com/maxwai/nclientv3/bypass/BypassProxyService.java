package com.maxwai.nclientv3.bypass;

import android.content.Intent;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleService;

import com.maxwai.nclientv3.R;

public class BypassProxyService extends LifecycleService {
    private static final int NOTIFICATION_ID = 4011;

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
            startForeground(
                NOTIFICATION_ID,
                BypassNotificationHelper.buildProxyNotification(
                    this,
                    getString(R.string.bypass_notification_preparing_proxy)
                )
            );
            BypassManager.getInstance().updateState(BypassMode.PROXY, BypassStage.STARTING, null);
            return START_STICKY;
        }
        if (BypassActions.ACTION_STOP_PROXY.equals(action)) {
            BypassManager.getInstance().updateState(BypassMode.DIRECT, BypassStage.IDLE, null);
            stopActiveForeground();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopActiveForeground();
        BypassState state = BypassManager.getInstance().getState();
        if (state.getMode() == BypassMode.PROXY && state.getStage() != BypassStage.RUNNING) {
            BypassManager.getInstance().updateState(BypassMode.DIRECT, BypassStage.IDLE, null);
        }
        super.onDestroy();
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
