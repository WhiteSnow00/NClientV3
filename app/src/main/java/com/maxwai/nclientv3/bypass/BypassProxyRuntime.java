package com.maxwai.nclientv3.bypass;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class BypassProxyRuntime {
    private final Object lock = new Object();
    private final ByeDpiProxyBridge proxyBridge = new ByeDpiProxyBridge();
    @Nullable
    private Thread workerThread;
    private volatile boolean stopRequested;

    public boolean start(@NonNull BypassProxyConfig config, @Nullable BypassSocketProtector protector, @NonNull ExitCallback callback) {
        synchronized (lock) {
            if (workerThread != null) {
                return false;
            }
            stopRequested = false;
            proxyBridge.setSocketProtector(protector);
            Thread thread = new Thread(() -> {
                int exitCode = proxyBridge.startProxy(config);
                proxyBridge.setSocketProtector(null);
                synchronized (lock) {
                    if (workerThread == Thread.currentThread()) {
                        workerThread = null;
                    }
                }
                callback.onProxyExit(exitCode, stopRequested);
            }, "NClientBypassProxy");
            workerThread = thread;
            thread.start();
            return true;
        }
    }

    public boolean waitUntilReachable(@NonNull String ip, int port, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isRunning()) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, port), 250);
                return true;
            } catch (IOException ignore) {
                SystemClock.sleep(100L);
            }
        }
        return false;
    }

    public boolean isRunning() {
        synchronized (lock) {
            return workerThread != null && workerThread.isAlive();
        }
    }

    public void stop(long joinTimeoutMs) {
        Thread thread;
        synchronized (lock) {
            stopRequested = true;
            thread = workerThread;
        }

        try {
            proxyBridge.stopProxy();
        } catch (Throwable t) {
            LogUtility.w("Error stopping proxy bridge", t);
        }

        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(joinTimeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                try {
                    proxyBridge.forceClose();
                } catch (Throwable t) {
                    LogUtility.w("Error force-closing proxy bridge", t);
                }
                try {
                    thread.join(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        proxyBridge.setSocketProtector(null);
        synchronized (lock) {
            if (thread == workerThread && (thread == null || !thread.isAlive())) {
                workerThread = null;
            }
        }
    }

    public interface ExitCallback {
        void onProxyExit(int exitCode, boolean stopRequested);
    }
}
