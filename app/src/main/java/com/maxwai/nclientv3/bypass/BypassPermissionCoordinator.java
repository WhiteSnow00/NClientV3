package com.maxwai.nclientv3.bypass;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.utility.AppExecutors;
import com.maxwai.nclientv3.utility.LogUtility;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BypassPermissionCoordinator {
    private static final BypassPermissionCoordinator INSTANCE = new BypassPermissionCoordinator();

    private final Object permissionLock = new Object();
    @Nullable
    private Context appContext;
    @Nullable
    private PendingPermissionRequest pendingRequest;

    private BypassPermissionCoordinator() {
    }

    @NonNull
    public static BypassPermissionCoordinator getInstance() {
        return INSTANCE;
    }

    public void initialize(@NonNull Context context) {
        appContext = context.getApplicationContext();
    }

    public boolean hasPermission() {
        Context context = appContext;
        return context != null && VpnService.prepare(context) == null;
    }

    public boolean ensurePermission(long timeoutMs) {
        Context context = requireContext();
        if (VpnService.prepare(context) == null) {
            return true;
        }

        PendingPermissionRequest request;
        boolean shouldLaunch = false;
        synchronized (permissionLock) {
            if (pendingRequest != null && !pendingRequest.completed) {
                request = pendingRequest;
            } else {
                request = new PendingPermissionRequest();
                pendingRequest = request;
                shouldLaunch = true;
            }
        }

        if (shouldLaunch) {
            launchPermissionActivity(context, request);
        }

        try {
            if (!request.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                LogUtility.w("Timed out waiting for VPN permission.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogUtility.w("Interrupted while waiting for VPN permission.", e);
        }

        return request.granted || VpnService.prepare(context) == null;
    }

    public void onPermissionResult(boolean granted) {
        PendingPermissionRequest request;
        synchronized (permissionLock) {
            request = pendingRequest;
            pendingRequest = null;
        }
        if (request == null) {
            return;
        }
        request.complete(granted);
    }

    private void launchPermissionActivity(@NonNull Context context, @NonNull PendingPermissionRequest request) {
        AppExecutors.main(context).execute(() -> {
            try {
                Intent intent = new Intent(context, BypassPermissionActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                context.startActivity(intent);
            } catch (Throwable t) {
                LogUtility.e("Unable to launch VPN permission activity", t);
                synchronized (permissionLock) {
                    if (pendingRequest == request) {
                        pendingRequest = null;
                    }
                }
                request.complete(false);
            }
        });
    }

    @NonNull
    private Context requireContext() {
        Context context = appContext;
        if (context == null) {
            throw new IllegalStateException("BypassPermissionCoordinator is not initialized");
        }
        return context;
    }

    private static final class PendingPermissionRequest {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean granted;
        private volatile boolean completed;

        void complete(boolean granted) {
            if (completed) {
                return;
            }
            this.granted = granted;
            completed = true;
            latch.countDown();
        }
    }
}
