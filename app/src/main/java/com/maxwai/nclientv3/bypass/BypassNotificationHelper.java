package com.maxwai.nclientv3.bypass;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.maxwai.nclientv3.MainActivity;
import com.maxwai.nclientv3.R;

public final class BypassNotificationHelper {
    public static final String VPN_CHANNEL_ID = "nclient_bypass_vpn";
    public static final String PROXY_CHANNEL_ID = "nclient_bypass_proxy";

    private BypassNotificationHelper() {
    }

    public static void ensureChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(
            new NotificationChannel(
                VPN_CHANNEL_ID,
                context.getString(R.string.bypass_vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        );
        manager.createNotificationChannel(
            new NotificationChannel(
                PROXY_CHANNEL_ID,
                context.getString(R.string.bypass_proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        );
    }

    @NonNull
    public static Notification buildVpnNotification(@NonNull Context context, @NonNull CharSequence text) {
        return buildNotification(context, VPN_CHANNEL_ID, text);
    }

    @NonNull
    public static Notification buildProxyNotification(@NonNull Context context, @NonNull CharSequence text) {
        return buildNotification(context, PROXY_CHANNEL_ID, text);
    }

    @NonNull
    private static Notification buildNotification(@NonNull Context context, @NonNull String channelId, @NonNull CharSequence text) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        return new NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_file)
            .setContentTitle(context.getString(R.string.bypass_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build();
    }
}
