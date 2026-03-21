package com.maxwai.nclientv3.bypass;

import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ServiceLifecycleDispatcher;

public class LifecycleTunnelVpnService extends VpnService implements LifecycleOwner {
    @SuppressWarnings("LeakingThis")
    private final ServiceLifecycleDispatcher dispatcher = new ServiceLifecycleDispatcher(this);

    @Override
    @CallSuper
    public void onCreate() {
        dispatcher.onServicePreSuperOnCreate();
        super.onCreate();
    }

    @Override
    @CallSuper
    public IBinder onBind(Intent intent) {
        dispatcher.onServicePreSuperOnBind();
        return super.onBind(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    @CallSuper
    public void onStart(Intent intent, int startId) {
        dispatcher.onServicePreSuperOnStart();
        super.onStart(intent, startId);
    }

    @Override
    @CallSuper
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    @CallSuper
    public void onDestroy() {
        dispatcher.onServicePreSuperOnDestroy();
        super.onDestroy();
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return dispatcher.getLifecycle();
    }
}
