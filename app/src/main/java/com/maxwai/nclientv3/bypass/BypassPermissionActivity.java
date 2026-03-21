package com.maxwai.nclientv3.bypass;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

import androidx.annotation.Nullable;

public final class BypassPermissionActivity extends Activity {
    private static final int REQUEST_CODE_VPN_PERMISSION = 4011;
    private static final String STATE_REQUESTED = "vpn_permission_requested";

    private boolean permissionRequested;
    private boolean completed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        permissionRequested = savedInstanceState != null && savedInstanceState.getBoolean(STATE_REQUESTED, false);
        requestPermissionIfNeeded();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_REQUESTED, permissionRequested);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_VPN_PERMISSION) {
            return;
        }
        boolean granted = resultCode == RESULT_OK || VpnService.prepare(this) == null;
        finishWithResult(granted);
    }

    @Override
    protected void onDestroy() {
        if (!completed && isFinishing()) {
            finishWithResult(VpnService.prepare(this) == null);
        }
        super.onDestroy();
    }

    private void requestPermissionIfNeeded() {
        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent == null) {
            finishWithResult(true);
            return;
        }
        if (permissionRequested) {
            return;
        }
        permissionRequested = true;
        startActivityForResult(prepareIntent, REQUEST_CODE_VPN_PERMISSION);
    }

    private void finishWithResult(boolean granted) {
        if (completed) {
            return;
        }
        completed = true;
        BypassPermissionCoordinator.getInstance().onPermissionResult(granted);
        finish();
        overridePendingTransition(0, 0);
    }
}
