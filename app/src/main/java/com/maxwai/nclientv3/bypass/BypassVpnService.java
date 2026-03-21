package com.maxwai.nclientv3.bypass;

import android.content.Intent;
import android.os.Build;

import androidx.annotation.Nullable;

import com.maxwai.nclientv3.R;

public class BypassVpnService extends LifecycleTunnelVpnService {
    private static final int NOTIFICATION_ID = 4010;

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
        if (BypassActions.ACTION_START_VPN.equals(action)) {
            startForeground(
                NOTIFICATION_ID,
                BypassNotificationHelper.buildVpnNotification(
                    this,
                    getString(R.string.bypass_notification_preparing_vpn)
                )
            );
            BypassManager.getInstance().updateState(BypassMode.VPN, BypassStage.STARTING, null);
            return START_STICKY;
        }
        if (BypassActions.ACTION_STOP_VPN.equals(action)) {
            BypassManager.getInstance().updateState(BypassMode.DIRECT, BypassStage.IDLE, null);
            stopActiveForeground();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        BypassManager.getInstance().updateState(BypassMode.DIRECT, BypassStage.IDLE, null);
        stopActiveForeground();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopActiveForeground();
        BypassState state = BypassManager.getInstance().getState();
        if (state.getMode() == BypassMode.VPN && state.getStage() != BypassStage.RUNNING) {
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
