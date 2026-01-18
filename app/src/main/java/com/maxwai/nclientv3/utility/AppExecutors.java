package com.maxwai.nclientv3.utility;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized executors for offloading disk/network work from the main thread.
 */
public final class AppExecutors {
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, new NamedThreadFactory("io"));

    private AppExecutors() {
    }

    @NonNull
    public static ExecutorService io() {
        return IO;
    }

    @NonNull
    public static Executor main(@NonNull Context context) {
        return ContextCompat.getMainExecutor(context.getApplicationContext());
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger nextId = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(r);
            t.setName("NClientV3-" + prefix + "-" + nextId.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
