package com.maxwai.nclientv3.settings;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.utility.LogUtility;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class NotificationSettings {

    private static final List<Integer> notificationArray = new CopyOnWriteArrayList<>();
    private static NotificationSettings notificationSettings;
    private static int notificationId = 999, maximumNotification;
    public static final int REQUEST_CODE_POST_NOTIFICATIONS = 2001;
    private final NotificationManagerCompat notificationManager;

    private NotificationSettings(NotificationManagerCompat notificationManager) {
        this.notificationManager = notificationManager;
    }

    public static int getNotificationId() {
        return notificationId++;
    }

    public static void initializeNotificationManager(Context context) {
        notificationSettings = new NotificationSettings(NotificationManagerCompat.from(context.getApplicationContext()));
        maximumNotification = context.getSharedPreferences("Settings", 0).getInt(context.getString(R.string.preference_key_maximum_notification), 25);
        trimArray();
    }

    public static boolean requestPostNotificationsIfNeeded(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (context instanceof Activity) {
            ActivityCompat.requestPermissions(
                (Activity) context,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_CODE_POST_NOTIFICATIONS
            );
        }
        return false;
    }

    public static void notify(Context context, int notificationId, Notification notification) {
        if (maximumNotification == 0) return;
        notificationArray.remove(Integer.valueOf(notificationId));
        notificationArray.add(notificationId);
        trimArray();
        LogUtility.d("Notification count: " + notificationArray.size());
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationSettings.notificationManager.notify(notificationId, notification);
    }

    public static void cancel(int notificationId) {
        notificationSettings.notificationManager.cancel(notificationId);
        notificationArray.remove(Integer.valueOf(notificationId));
    }

    private static void trimArray() {
        while (notificationArray.size() > maximumNotification) {
            int first = notificationArray.remove(0);
            notificationSettings.notificationManager.cancel(first);
        }
    }
}
