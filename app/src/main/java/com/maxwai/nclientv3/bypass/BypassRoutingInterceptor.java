package com.maxwai.nclientv3.bypass;

import androidx.annotation.NonNull;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public final class BypassRoutingInterceptor implements Interceptor {
    private static final String HEADER_BYPASS_RETRY = "X-NClient-Bypass-Retry";
    private final BypassNetworkController controller;

    public BypassRoutingInterceptor(@NonNull BypassNetworkController controller) {
        this.controller = controller;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request internalRequest = chain.request();
        boolean alreadyRetried = internalRequest.header(HEADER_BYPASS_RETRY) != null;
        Request request = internalRequest.newBuilder().removeHeader(HEADER_BYPASS_RETRY).build();
        controller.onRequestStarted(request);
        try {
            Response response = chain.proceed(request);
            controller.onRequestFinished(request, response, null);
            return response;
        } catch (IOException e) {
            controller.onRequestFinished(request, null, e);
            if (!controller.shouldRetryWithBypass(request, e, alreadyRetried)) {
                throw e;
            }

            Request retryRequest = request.newBuilder().header(HEADER_BYPASS_RETRY, "1").build();
            controller.onRequestStarted(retryRequest);
            try {
                Response retryResponse = chain.proceed(retryRequest.newBuilder().removeHeader(HEADER_BYPASS_RETRY).build());
                controller.onRequestFinished(retryRequest, retryResponse, null);
                return retryResponse;
            } catch (IOException retryException) {
                controller.onRequestFinished(retryRequest, null, retryException);
                throw retryException;
            }
        }
    }
}
