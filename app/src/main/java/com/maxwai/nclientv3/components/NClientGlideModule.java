package com.maxwai.nclientv3.components;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.maxwai.nclientv3.settings.Global;

import java.io.InputStream;

@GlideModule
public class NClientGlideModule extends AppGlideModule {
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.replace(
            GlideUrl.class,
            InputStream.class,
            new OkHttpUrlLoader.Factory(Global.getClient(context.getApplicationContext()))
        );
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
