package com.maxwai.nclientv3.bypass;

import androidx.annotation.NonNull;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public final class BypassRoutingInterceptor implements Interceptor {
    private final BypassNetworkController controller;

    public BypassRoutingInterceptor(@NonNull BypassNetworkController controller) {
        this.controller = controller;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        controller.onRequestStarted(request);
        try {
            Response response = chain.proceed(request);
            controller.onRequestFinished(request, response, null);
            return response;
        } catch (IOException e) {
            controller.onRequestFinished(request, null, e);
            throw e;
        }
    }
}
