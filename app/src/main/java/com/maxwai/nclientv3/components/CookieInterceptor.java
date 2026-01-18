package com.maxwai.nclientv3.components;

import android.view.View;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.components.views.CFTokenView;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.util.HashMap;

public class CookieInterceptor {
    private static volatile boolean webViewHidden = false;

    public static void hideWebView() {
        webViewHidden = true;
        CFTokenView tokenView = GeneralActivity.getLastCFView();
        if (tokenView != null) {
            tokenView.post(() -> tokenView.setVisibility(View.GONE));
        }
    }

    @NonNull
    private final Manager manager;

    public CookieInterceptor(@NonNull Manager manager) {
        this.manager = manager;
    }

    private void maybeStartCloudflareFlow() {
        CFTokenView tokenView = GeneralActivity.getLastCFView();
        if (tokenView == null) return;
        tokenView.post(() -> {
            CFTokenView.CFTokenWebView webView = tokenView.getWebView();
            webView.loadUrl(Utility.getBaseUrl());
            if (!webViewHidden) tokenView.setVisibility(View.VISIBLE);
        });
    }

    /**
     * Fail-fast cookie interception: never busy-waits and never depends on an Activity/WebView being alive.
     * If required cookies are missing, triggers the existing Cloudflare/WebView flow (if available) and throws.
     */
    public void intercept() throws IOException {
        if (manager.endInterceptor()) {
            manager.onFinish();
            return;
        }
        String cookies = CookieManager.getInstance().getCookie(Utility.getBaseUrl());
        if (cookies != null) {
            HashMap<String, String> cookiesMap = new HashMap<>();
            String[] splitCookies = cookies.split("; ");
            for (String splitCookie : splitCookies) {
                String[] kv = splitCookie.split("=", 2);
                if (kv.length != 2) continue;
                if (!kv[1].equals(cookiesMap.put(kv[0], kv[1]))) {
                    LogUtility.d("Processing cookie: " + kv[0] + "=" + kv[1]);
                    manager.applyCookie(kv[0], kv[1]);
                }
            }
        }

        if (!manager.endInterceptor()) {
            // Kick the UI flow if possible, but do not wait for it.
            maybeStartCloudflareFlow();
            throw new IOException("Cloudflare challenge required (missing cookies/token).");
        }
        CFTokenView tokenView = GeneralActivity.getLastCFView();
        if (tokenView != null) tokenView.post(() -> tokenView.setVisibility(View.GONE));
        manager.onFinish();
    }

    public interface Manager {
        void applyCookie(String key, String value);

        boolean endInterceptor();

        void onFinish();
    }
}
