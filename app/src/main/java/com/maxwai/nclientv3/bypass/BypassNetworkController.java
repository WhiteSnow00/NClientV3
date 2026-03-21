package com.maxwai.nclientv3.bypass;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Request;
import okhttp3.Response;

public final class BypassNetworkController implements BypassStateStore.Listener {
    private static final BypassNetworkController INSTANCE = new BypassNetworkController();
    private static final long VPN_PERMISSION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(45);
    private static final long VPN_START_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15);
    private static final long VPN_STOP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(8);
    private static final long DIRECT_PROBE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(3);
    private static final long INVALID_CONTENT_WINDOW_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long ACTIVATION_DEBOUNCE_MS = TimeUnit.SECONDS.toMillis(20);
    private static final long DIRECT_PROBE_BASE_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(3);
    private static final long DIRECT_PROBE_MAX_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(15);

    private final ConcurrentHashMap<String, HostAccessRecord> hostAccess = new ConcurrentHashMap<>();
    @Nullable
    private Context appContext;

    private BypassNetworkController() {
    }

    @NonNull
    public static BypassNetworkController getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize(@NonNull Context context) {
        if (appContext != null) return;
        appContext = context.getApplicationContext();
        BypassPermissionCoordinator.getInstance().initialize(appContext);
        BypassManager.getInstance().getStateStore().addListener(this);
    }

    public void onRequestStarted(@NonNull Request request) {
        if (!shouldManageHost(request.url().host())) return;
        HostAccessRecord record = recordFor(request.url().host());
        record.markStarted();
        maybeRecoverDirectRoute(request, record);
    }

    public void onRequestFinished(@NonNull Request request, @Nullable Response response, @Nullable IOException exception) {
        if (!shouldManageHost(request.url().host())) return;
        HostAccessRecord record = recordFor(request.url().host());
        record.markFinished(response, exception);
        if (response != null && response.isSuccessful() && !isVpnRunning()) {
            record.markDirectSuccess();
        }
    }

    public boolean shouldRetryWithBypass(@NonNull Request request, @NonNull IOException exception, boolean alreadyRetried) {
        String host = request.url().host();
        if (alreadyRetried || !shouldManageHost(host) || !hasInternetConnectivity()) {
            return false;
        }
        if (looksLikeCloudflareChallenge(exception.getMessage()) || !isBlockingFailure(exception)) {
            return false;
        }

        HostAccessRecord record = recordFor(host);
        long now = System.currentTimeMillis();
        if (!record.markActivationAttemptIfAllowed(now)) {
            return false;
        }
        return ensureBypassForHost(host, record, "request failure");
    }

    public boolean prepareInvalidContentRetry(@Nullable String url, @Nullable String primaryHint, @Nullable String secondaryHint) {
        String host = extractHost(url);
        if (host.isEmpty() || !shouldManageHost(host) || !hasInternetConnectivity()) {
            return false;
        }

        String mergedHint = normalizeMessage(primaryHint + " " + secondaryHint);
        if (looksLikeCloudflareChallenge(mergedHint)) {
            return false;
        }

        HostAccessRecord record = recordFor(host);
        long now = System.currentTimeMillis();
        boolean immediate = looksLikeBlockPage(primaryHint, secondaryHint) || looksLikeUnexpectedTextPayload(primaryHint, secondaryHint);
        if (!record.markInvalidContent(now, immediate)) {
            return false;
        }
        if (!record.markActivationAttemptIfAllowed(now)) {
            return false;
        }
        return ensureBypassForHost(host, record, "invalid content");
    }

    @Nullable
    public HostAccessSnapshot snapshotForHost(@Nullable String host) {
        if (host == null) return null;
        HostAccessRecord record = hostAccess.get(normalizeHost(host));
        return record == null ? null : record.snapshot();
    }

    @Override
    public void onBypassStateChanged(@NonNull BypassState state) {
        Global.evictHttpConnections();
    }

    private void maybeRecoverDirectRoute(@NonNull Request request, @NonNull HostAccessRecord record) {
        long now = System.currentTimeMillis();
        if (!isVpnRunning() || !record.shouldProbeDirect(now) || !hasInternetConnectivity()) {
            return;
        }
        if (!record.beginProbe()) {
            return;
        }

        boolean directHealthy = false;
        try {
            directHealthy = probeDirectConnection(request);
        } finally {
            if (directHealthy) {
                record.markDirectRecovered();
            } else {
                record.markProbeFailed(System.currentTimeMillis());
            }
            record.endProbe();
        }

        if (!directHealthy) {
            return;
        }

        if (hasBlockedHosts()) {
            BypassManager.getInstance().updateState(BypassMode.VPN, BypassStage.RUNNING, firstBlockedHost());
            return;
        }

        BypassManager.getInstance().stop();
        waitForState(BypassMode.DIRECT, BypassStage.IDLE, VPN_STOP_TIMEOUT_MS);
    }

    private boolean ensureBypassForHost(@NonNull String host, @NonNull HostAccessRecord record, @NonNull String reason) {
        BypassState state = BypassManager.getInstance().getState();
        if (state.getMode() == BypassMode.VPN && state.getStage() == BypassStage.RUNNING) {
            record.markBypassRequired(System.currentTimeMillis());
            BypassManager.getInstance().updateState(BypassMode.VPN, BypassStage.RUNNING, host);
            return true;
        }

        if (state.getMode() == BypassMode.VPN && state.getStage() == BypassStage.STARTING) {
            boolean running = waitForState(BypassMode.VPN, BypassStage.RUNNING, VPN_START_TIMEOUT_MS);
            if (running) {
                record.markBypassRequired(System.currentTimeMillis());
                BypassManager.getInstance().updateState(BypassMode.VPN, BypassStage.RUNNING, host);
            }
            return running;
        }

        if (!BypassPermissionCoordinator.getInstance().ensurePermission(VPN_PERMISSION_TIMEOUT_MS)) {
            LogUtility.w("VPN permission was not granted. Bypass retry skipped for host ", host);
            return false;
        }

        LogUtility.i("Activating VPN bypass for host ", host, " because of ", reason);
        BypassManager.getInstance().startVpn();
        boolean running = waitForState(BypassMode.VPN, BypassStage.RUNNING, VPN_START_TIMEOUT_MS);
        if (!running) {
            LogUtility.w("VPN bypass did not become ready in time for host ", host);
            return false;
        }

        record.markBypassRequired(System.currentTimeMillis());
        BypassManager.getInstance().updateState(BypassMode.VPN, BypassStage.RUNNING, host);
        return true;
    }

    private boolean waitForState(@NonNull BypassMode mode, @NonNull BypassStage stage, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            BypassState state = BypassManager.getInstance().getState();
            if (state.getMode() == mode && state.getStage() == stage) {
                return true;
            }
            if (state.getStage() == BypassStage.FAILED) {
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        BypassState state = BypassManager.getInstance().getState();
        return state.getMode() == mode && state.getStage() == stage;
    }

    private boolean probeDirectConnection(@NonNull Request request) {
        String host = request.url().host();
        int port = request.url().port();
        String scheme = request.url().scheme();

        try (Socket socket = new Socket()) {
            socket.setSoTimeout((int) DIRECT_PROBE_TIMEOUT_MS);
            if (!BypassManager.getInstance().protectSocket(socket)) {
                LogUtility.w("Unable to protect direct recovery socket for host ", host);
                return false;
            }
            socket.connect(new InetSocketAddress(host, port), (int) DIRECT_PROBE_TIMEOUT_MS);

            if ("https".equalsIgnoreCase(scheme)) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket sslSocket = (SSLSocket) factory.createSocket(socket, host, port, true)) {
                    sslSocket.setSoTimeout((int) DIRECT_PROBE_TIMEOUT_MS);
                    sslSocket.startHandshake();
                    return sendProbeRequest(sslSocket, request);
                }
            }
            return sendProbeRequest(socket, request);
        } catch (IOException e) {
            LogUtility.d("Direct recovery probe failed for host ", host, ": ", e.getClass().getSimpleName(), " ", e.getMessage());
            return false;
        }
    }

    private boolean sendProbeRequest(@NonNull Socket socket, @NonNull Request request) throws IOException {
        String path = request.url().encodedPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = request.url().encodedQuery();
        if (query != null && !query.isEmpty()) {
            path = path + '?' + query;
        }

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        writer.write("HEAD " + path + " HTTP/1.1\r\n");
        writer.write("Host: " + request.url().host() + "\r\n");
        writer.write("User-Agent: " + Global.getUserAgent() + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String statusLine = reader.readLine();
        return statusLine != null && statusLine.startsWith("HTTP/");
    }

    private boolean hasInternetConnectivity() {
        Context context = appContext;
        if (context == null) {
            return true;
        }
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return true;
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private boolean shouldManageHost(@Nullable String host) {
        String normalizedHost = normalizeHost(host);
        if (normalizedHost.isEmpty()) {
            return false;
        }
        String mirrorHost = normalizeHost(Utility.getHost());
        if (mirrorHost.isEmpty()) {
            return false;
        }
        return normalizedHost.equals(mirrorHost) || normalizedHost.endsWith("." + mirrorHost);
    }

    private boolean isVpnRunning() {
        BypassState state = BypassManager.getInstance().getState();
        return state.getMode() == BypassMode.VPN && state.getStage() == BypassStage.RUNNING;
    }

    private boolean hasBlockedHosts() {
        for (HostAccessRecord record : hostAccess.values()) {
            if (record.isBypassRequired()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private String firstBlockedHost() {
        for (Map.Entry<String, HostAccessRecord> entry : hostAccess.entrySet()) {
            if (entry.getValue().isBypassRequired()) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean isBlockingFailure(@NonNull IOException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException ||
                current instanceof ConnectException ||
                current instanceof NoRouteToHostException ||
                current instanceof PortUnreachableException ||
                current instanceof UnknownHostException) {
                return true;
            }

            String message = normalizeMessage(current.getMessage());
            if (current instanceof SocketException) {
                if (message.contains("connection reset") ||
                    message.contains("connection refused") ||
                    message.contains("connection abort") ||
                    message.contains("network is unreachable") ||
                    message.contains("host is unreachable") ||
                    message.contains("broken pipe") ||
                    message.contains("timed out")) {
                    return true;
                }
            }

            if (current instanceof SSLException) {
                if (message.contains("handshake") ||
                    message.contains("connection reset") ||
                    message.contains("unexpected end of stream") ||
                    message.contains("broken pipe") ||
                    message.contains("protocol error")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean looksLikeBlockPage(@Nullable String primaryHint, @Nullable String secondaryHint) {
        String hint = normalizeMessage(primaryHint + " " + secondaryHint);
        if (hint.isEmpty()) {
            return false;
        }
        return hint.contains("access denied") ||
            hint.contains("website blocked") ||
            hint.contains("site blocked") ||
            hint.contains("restricted") ||
            hint.contains("blocked by") ||
            hint.contains("not available in your country") ||
            hint.contains("forbidden") ||
            hint.contains("denied access");
    }

    private boolean looksLikeUnexpectedTextPayload(@Nullable String primaryHint, @Nullable String secondaryHint) {
        String hint = normalizeMessage(primaryHint + " " + secondaryHint);
        return hint.contains("content-type: text/html") ||
            hint.contains("content-type: text/plain") ||
            hint.contains("mime=text/html") ||
            hint.contains("mime=text/plain");
    }

    private boolean looksLikeCloudflareChallenge(@Nullable String hint) {
        String message = normalizeMessage(hint);
        return message.contains("cloudflare") ||
            message.contains("attention required") ||
            message.contains("challenge required");
    }

    @NonNull
    private String extractHost(@Nullable String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            return normalizeHost(uri.getHost());
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    @NonNull
    private HostAccessRecord recordFor(@Nullable String host) {
        return hostAccess.computeIfAbsent(normalizeHost(host), ignored -> new HostAccessRecord());
    }

    @NonNull
    private String normalizeHost(@Nullable String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    @NonNull
    private String normalizeMessage(@Nullable String message) {
        return message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
    }

    public static final class HostAccessSnapshot {
        public final long startedAt;
        public final long finishedAt;
        public final long successAt;
        public final long failureAt;
        public final long nextDirectProbeAt;
        public final int lastStatusCode;
        public final boolean bypassRequired;
        @Nullable
        public final String lastFailureClass;

        private HostAccessSnapshot(
            long startedAt,
            long finishedAt,
            long successAt,
            long failureAt,
            long nextDirectProbeAt,
            int lastStatusCode,
            boolean bypassRequired,
            @Nullable String lastFailureClass
        ) {
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.successAt = successAt;
            this.failureAt = failureAt;
            this.nextDirectProbeAt = nextDirectProbeAt;
            this.lastStatusCode = lastStatusCode;
            this.bypassRequired = bypassRequired;
            this.lastFailureClass = lastFailureClass;
        }
    }

    private static final class HostAccessRecord {
        private volatile long startedAt;
        private volatile long finishedAt;
        private volatile long successAt;
        private volatile long failureAt;
        private volatile long nextDirectProbeAt;
        private volatile int lastStatusCode;
        private volatile boolean bypassRequired;
        @Nullable
        private volatile String lastFailureClass;
        private volatile long lastActivationAttemptAt;
        private volatile long lastInvalidContentAt;
        private volatile int invalidContentCount;
        private volatile long directProbeCooldownMs = DIRECT_PROBE_BASE_COOLDOWN_MS;
        private volatile boolean probeInFlight;

        void markStarted() {
            startedAt = System.currentTimeMillis();
        }

        void markFinished(@Nullable Response response, @Nullable IOException exception) {
            finishedAt = System.currentTimeMillis();
            if (response != null) {
                lastStatusCode = response.code();
                if (response.isSuccessful()) {
                    successAt = finishedAt;
                    lastFailureClass = null;
                }
            }
            if (exception != null) {
                failureAt = finishedAt;
                lastFailureClass = exception.getClass().getName();
            }
        }

        synchronized boolean markActivationAttemptIfAllowed(long now) {
            if (now - lastActivationAttemptAt < ACTIVATION_DEBOUNCE_MS) {
                return false;
            }
            lastActivationAttemptAt = now;
            return true;
        }

        synchronized boolean markInvalidContent(long now, boolean immediate) {
            if (now - lastInvalidContentAt > INVALID_CONTENT_WINDOW_MS) {
                invalidContentCount = 0;
            }
            lastInvalidContentAt = now;
            invalidContentCount++;
            return immediate || invalidContentCount >= 2;
        }

        synchronized void markBypassRequired(long now) {
            bypassRequired = true;
            nextDirectProbeAt = now + directProbeCooldownMs;
            invalidContentCount = 0;
        }

        synchronized void markDirectSuccess() {
            bypassRequired = false;
            nextDirectProbeAt = 0L;
            invalidContentCount = 0;
            directProbeCooldownMs = DIRECT_PROBE_BASE_COOLDOWN_MS;
        }

        synchronized boolean shouldProbeDirect(long now) {
            return bypassRequired && !probeInFlight && now >= nextDirectProbeAt;
        }

        synchronized boolean beginProbe() {
            if (probeInFlight) {
                return false;
            }
            probeInFlight = true;
            return true;
        }

        synchronized void endProbe() {
            probeInFlight = false;
        }

        synchronized void markProbeFailed(long now) {
            nextDirectProbeAt = now + directProbeCooldownMs;
            directProbeCooldownMs = Math.min(DIRECT_PROBE_MAX_COOLDOWN_MS, directProbeCooldownMs * 2L);
        }

        synchronized void markDirectRecovered() {
            bypassRequired = false;
            nextDirectProbeAt = 0L;
            invalidContentCount = 0;
            directProbeCooldownMs = DIRECT_PROBE_BASE_COOLDOWN_MS;
        }

        boolean isBypassRequired() {
            return bypassRequired;
        }

        @NonNull
        HostAccessSnapshot snapshot() {
            return new HostAccessSnapshot(
                startedAt,
                finishedAt,
                successAt,
                failureAt,
                nextDirectProbeAt,
                lastStatusCode,
                bypassRequired,
                lastFailureClass
            );
        }
    }
}
