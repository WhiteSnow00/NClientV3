package com.maxwai.nclientv3.bypass;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.MainActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class BypassVpnService extends LifecycleTunnelVpnService implements BypassSocketProtector {
    private static final int NOTIFICATION_ID = 4010;
    private static final long PROXY_READY_TIMEOUT_MS = 5000L;

    private final Object serviceLock = new Object();
    private final BypassProxyRuntime proxyRuntime = new BypassProxyRuntime();
    @Nullable
    private Thread startupThread;
    @Nullable
    private ParcelFileDescriptor tunFd;
    @Nullable
    private File tunConfigFile;
    private volatile boolean tunnelStarted;
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
        if (BypassActions.ACTION_START_VPN.equals(action)) {
            handleStart();
            return START_STICKY;
        }
        if (BypassActions.ACTION_STOP_VPN.equals(action)) {
            new Thread(this::stopInternal, "NClientBypassVpnStop").start();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        new Thread(this::stopInternal, "NClientBypassVpnRevoke").start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean protectSocket(int fd) {
        try {
            return protect(fd);
        } catch (Exception e) {
            LogUtility.w("Unable to protect socket", e);
            return false;
        }
    }

    private void handleStart() {
        synchronized (serviceLock) {
            if (proxyRuntime.isRunning() || startupThread != null || tunnelStarted) {
                return;
            }
            stopping = false;
        }

        startForegroundCompat(
            BypassNotificationHelper.buildVpnNotification(
                this,
                getString(R.string.bypass_notification_preparing_vpn)
            )
        );
        BypassManager.getInstance().updateState(BypassMode.VPN, BypassStage.STARTING, null);

        BypassProxyConfig config = BypassProxyConfig.fromContext(this);
        if (!proxyRuntime.start(config, this, this::onProxyExit)) {
            return;
        }

        Thread thread = new Thread(() -> awaitTunnel(config), "NClientBypassVpnStart");
        synchronized (serviceLock) {
            startupThread = thread;
        }
        thread.start();
    }

    private void awaitTunnel(@NonNull BypassProxyConfig config) {
        try {
            boolean ready = proxyRuntime.waitUntilReachable(config.getProxyIp(), config.getProxyPort(), PROXY_READY_TIMEOUT_MS);
            if (!ready) {
                failAndStop("Local proxy did not become reachable before tunnel start.");
                return;
            }
            if (!startTun2Socks(config)) {
                failAndStop("Unable to establish tun2socks.");
                return;
            }
            tunnelStarted = true;
            updateNotification(getString(R.string.bypass_notification_vpn_active));
            BypassManager.getInstance().updateState(BypassMode.VPN, BypassStage.RUNNING, null);
        } finally {
            synchronized (serviceLock) {
                if (startupThread == Thread.currentThread()) {
                    startupThread = null;
                }
            }
        }
    }

    private boolean startTun2Socks(@NonNull BypassProxyConfig config) {
        try {
            File configFile = writeTunnelConfig(config);
            ParcelFileDescriptor descriptor = createBuilder(config).establish();
            if (descriptor == null) {
                if (!configFile.delete()) {
                    LogUtility.w("Unable to delete failed tunnel config file: ", configFile);
                }
                return false;
            }
            tunConfigFile = configFile;
            tunFd = descriptor;
            TProxyService.TProxyStartService(configFile.getAbsolutePath(), descriptor.getFd());
            return true;
        } catch (IOException | PackageManager.NameNotFoundException | SecurityException e) {
            LogUtility.e("Error starting tun2socks", e);
            closeTunnelFd();
            deleteTunnelConfig();
            return false;
        }
    }

    @NonNull
    private VpnService.Builder createBuilder(@NonNull BypassProxyConfig config) throws PackageManager.NameNotFoundException {
        VpnService.Builder builder = new VpnService.Builder()
            .setSession("NClientV3 Bypass")
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication(getPackageName());

        if (config.isIpv6Enabled()) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0);
        }
        if (!config.getDnsIp().trim().isEmpty()) {
            builder.addDnsServer(config.getDnsIp());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }
        return builder;
    }

    @NonNull
    private File writeTunnelConfig(@NonNull BypassProxyConfig config) throws IOException {
        File configFile = File.createTempFile("nclient-bypass", ".yml", getCacheDir());
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(
                "misc:\n" +
                    "  task-stack-size: 81920\n" +
                    "socks5:\n" +
                    "  mtu: 8500\n" +
                    "  address: " + config.getProxyIp() + "\n" +
                    "  port: " + config.getProxyPort() + "\n" +
                    "  udp: udp\n"
            );
            writer.flush();
        }
        return configFile;
    }

    private void onProxyExit(int exitCode, boolean stopRequested) {
        if (stopRequested) {
            return;
        }
        LogUtility.w("Local proxy exited while VPN was active. Exit code=", exitCode);
        BypassManager.getInstance().updateState(BypassMode.VPN, BypassStage.FAILED, null);
        new Thread(() -> stopInternal(false), "NClientBypassVpnProxyExit").start();
    }

    private void failAndStop(String message) {
        if (stopping) return;
        LogUtility.w(message);
        BypassManager.getInstance().updateState(BypassMode.VPN, BypassStage.FAILED, null);
        new Thread(() -> stopInternal(false), "NClientBypassVpnFail").start();
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

        tunnelStarted = false;
        try {
            TProxyService.TProxyStopService();
        } catch (Throwable t) {
            LogUtility.w("Error stopping tun2socks", t);
        }
        closeTunnelFd();
        deleteTunnelConfig();
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
            BypassNotificationHelper.buildVpnNotification(this, text)
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

    private void closeTunnelFd() {
        ParcelFileDescriptor descriptor = tunFd;
        tunFd = null;
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException e) {
            LogUtility.w("Error closing tunnel descriptor", e);
        }
    }

    private void deleteTunnelConfig() {
        File configFile = tunConfigFile;
        tunConfigFile = null;
        if (configFile != null && configFile.exists() && !configFile.delete()) {
            LogUtility.w("Unable to delete tunnel config file: ", configFile);
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
