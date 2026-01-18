package com.maxwai.nclientv3.components.views;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.load.resource.bitmap.Rotate;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.ImageViewTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.ZoomActivity;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.components.GlideX;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.files.PageFile;
import com.maxwai.nclientv3.github.chrisbanes.photoview.PhotoView;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;

public class ZoomFragment extends Fragment {

    public interface OnZoomChangeListener {
        void onZoomChange(View v, float zoomLevel);
    }

    private static final float MAX_SCALE = 8f;
    private static final float CHANGE_PAGE_THRESHOLD = .2f;
    private static final long MAX_DECODE_PIXELS = 8_000_000L;
    private static final float UPSCALE_TOLERANCE = 1.05f;
    private static final float AUTO_UPGRADE_DISPLAY_OVERSAMPLE = 1.20f;
    private PhotoView photoView = null;
    private ImageButton retryButton;
    private PageFile pageFile = null;
    private Uri url;
    private int degree = 0;
    private boolean completedDownload = false;
    private View.OnClickListener clickListener;
    private OnZoomChangeListener zoomChangeListener;
    private ImageViewTarget<Drawable> target = null;
    private int originalW = 0;
    private int originalH = 0;
    private int lastRequestedW = 0;
    private int lastRequestedH = 0;
    private int lastRequestedDegree = 0;
    private int lastQualityLevel = 0;
    private int requestedQualityLevel = 0;
    private float baseScale = 1f;
    private boolean autoUpgradeAttempted = false;
    private boolean autoUpgradeInFlight = false;
    private String autoUpgradeKey = null;
    @Nullable
    private Matrix autoUpgradePreservedDisplayMatrix = null;
    private int autoUpgradeRequestGeneration = -1;
    private boolean preserveDisplayMatrixDuringAutoUpgrade = false;
    private int requestGeneration = 0;


    public ZoomFragment() {
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory) {
        Bundle args = new Bundle();
        args.putString("URL", gallery.isLocal() ? null : ((Gallery) gallery).getPageUrl(page).toString());
        args.putParcelable("FOLDER", directory == null ? null : directory.getPage(page + 1));
        try {
            args.putInt("PAGE_W", gallery.getGalleryData().getPage(page).getSize().getWidth());
            args.putInt("PAGE_H", gallery.getGalleryData().getPage(page).getSize().getHeight());
        } catch (Exception ignore) {
        }
        ZoomFragment fragment = new ZoomFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public void setClickListener(View.OnClickListener clickListener) {
        this.clickListener = clickListener;
    }


    public void setZoomChangeListener(OnZoomChangeListener zoomChangeListener) {
        this.zoomChangeListener = zoomChangeListener;
    }

    private float calculateScaleFactor(int width, int height) {
        FragmentActivity activity = getActivity();
        if (height < width * 2) return Global.getDefaultZoom();
        float finalSize =
            ((float) Global.getDeviceWidth(activity) * height) /
                ((float) Global.getDeviceHeight(activity) * width);
        finalSize = Math.max(finalSize, Global.getDefaultZoom());
        finalSize = Math.min(finalSize, MAX_SCALE);
        LogUtility.d("Final scale: " + finalSize);
        return (float) Math.floor(finalSize);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_zoom, container, false);
        ZoomActivity activity = (ZoomActivity) getActivity();
        assert getArguments() != null;
        assert activity != null;
        photoView = rootView.findViewById(R.id.image);
        retryButton = rootView.findViewById(R.id.imageView);
        String str = getArguments().getString("URL");
        url = str == null ? null : Uri.parse(str);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pageFile = getArguments().getParcelable("FOLDER", PageFile.class);
        } else {
            pageFile = getArguments().getParcelable("FOLDER");
        }
        originalW = getArguments().getInt("PAGE_W", 0);
        originalH = getArguments().getInt("PAGE_H", 0);
        photoView.setAllowParentInterceptOnEdge(true);
        photoView.setOnPhotoTapListener((view, x, y) -> {
            boolean prev = x < CHANGE_PAGE_THRESHOLD;
            boolean next = x > 1f - CHANGE_PAGE_THRESHOLD;
            if ((prev || next) && Global.isButtonChangePage()) {
                activity.changeClosePage(next);
            } else if (clickListener != null) {
                clickListener.onClick(view);
            }
            LogUtility.d(view, x, y, prev, next);
        });

        photoView.setOnScaleChangeListener((float scaleFactor, float focusX, float focusY) -> {
            if (this.zoomChangeListener != null) {
                this.zoomChangeListener.onZoomChange(rootView, photoView.getScale());
            }
            maybeUpgradeDecodeForZoom(photoView.getScale());
        });

        photoView.setMaximumScale(MAX_SCALE);
        retryButton.setOnClickListener(v -> loadImage());
        createTarget();
        loadImage();
        return rootView;
    }

    private void createTarget() {
        target = new ImageViewTarget<Drawable>(photoView) {

            @Override
            protected void setResource(@Nullable Drawable resource) {
                photoView.setImageDrawable(resource);
            }

            void applyDrawable(ImageView toShow, ImageView toHide, Drawable drawable) {
                toShow.setVisibility(View.VISIBLE);
                toHide.setVisibility(View.GONE);
                toShow.setImageDrawable(drawable);
                if (toShow instanceof PhotoView) {
                    if (preserveDisplayMatrixDuringAutoUpgrade && autoUpgradePreservedDisplayMatrix != null) {
                        ((PhotoView) toShow).setSuppMatrix(autoUpgradePreservedDisplayMatrix);
                    } else {
                        scalePhoto(drawable);
                    }
                }
            }

            @Override
            public void onLoadStarted(@Nullable Drawable placeholder) {
                super.onLoadStarted(placeholder);
                applyDrawable(photoView, retryButton, placeholder);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                super.onLoadFailed(errorDrawable);
                applyDrawable(retryButton, photoView, errorDrawable);
                if (autoUpgradeRequestGeneration == requestGeneration) {
                    preserveDisplayMatrixDuringAutoUpgrade = false;
                    autoUpgradePreservedDisplayMatrix = null;
                    autoUpgradeInFlight = false;
                    autoUpgradeRequestGeneration = -1;
                }
            }

            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                applyDrawable(photoView, retryButton, resource);
                if (resource instanceof Animatable)
                    ((GifDrawable) resource).start();
                int generation = requestGeneration;
                if (autoUpgradeRequestGeneration == generation) {
                    preserveDisplayMatrixDuringAutoUpgrade = false;
                    autoUpgradePreservedDisplayMatrix = null;
                    autoUpgradeInFlight = false;
                    autoUpgradeRequestGeneration = -1;
                }
                scheduleAutoUpgradeCheck(generation);
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                super.onLoadCleared(placeholder);
                applyDrawable(photoView, retryButton, placeholder);
            }
        };
    }

    private void scalePhoto(Drawable drawable) {
        int w = originalW > 0 ? originalW : drawable.getIntrinsicWidth();
        int h = originalH > 0 ? originalH : drawable.getIntrinsicHeight();
        baseScale = calculateScaleFactor(w, h);
        photoView.setScale(baseScale, 0, 0, false);
    }

    public void loadImage() {
        loadImage(Priority.NORMAL);
    }

    public void loadImage(Priority priority) {
        if (photoView == null) return;
        float scaleHint = photoView.getDrawable() != null ? photoView.getScale() : estimateInitialScale();
        scaleHint = clampDecodeScale(scaleHint);
        int qualityLevel = requestedQualityLevel > 0 ? requestedQualityLevel : qualityLevelForScale(scaleHint);
        int[] decodeSize = computeDecodeSizePx(scaleHint, oversampleForLevel(qualityLevel));
        if (completedDownload
            && lastRequestedDegree == degree
            && lastRequestedW == decodeSize[0]
            && lastRequestedH == decodeSize[1]) {
            return;
        }
        requestGeneration++;
        cancelRequest();
        RequestBuilder<Drawable> dra = loadPage();
        if (dra == null) return;
        completedDownload = false;
        lastRequestedDegree = degree;
        lastRequestedW = decodeSize[0];
        lastRequestedH = decodeSize[1];
        lastQualityLevel = qualityLevel;
        requestedQualityLevel = qualityLevel;
        dra
            .transform(new Rotate(degree))
            .apply(new RequestOptions()
                .fitCenter()
                .downsample(DownsampleStrategy.FIT_CENTER)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .override(decodeSize[0], decodeSize[1]))
            .placeholder(R.drawable.ic_launcher_foreground)
            .error(R.drawable.ic_refresh)
            .priority(priority)
            .addListener(new RequestListener<>() {
                @Override
                public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                    completedDownload = false;
                    return false;
                }

                @Override
                public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                    completedDownload = true;
                    return false;
                }
            })
            .into(this.target);
    }

    @Nullable
    private RequestBuilder<Drawable> loadPage() {
        RequestBuilder<Drawable> request;
        RequestManager glide = GlideX.with(photoView);
        if (glide == null) return null;
        if (pageFile != null) {
            request = glide.load(pageFile);
            LogUtility.d("Requested file glide: " + pageFile);
        } else {
            if (url == null) request = glide.load(R.mipmap.ic_launcher);
            else {
                LogUtility.d("Requested url glide: " + url);
                request = glide.load(url);
            }
        }
        return request;
    }

    public Drawable getDrawable() {
        return photoView.getDrawable();
    }

    @Nullable
    public File getPageFile() {
        return pageFile;
    }

    public void cancelRequest() {
        if (photoView != null && target != null) {
            RequestManager manager = GlideX.with(photoView);
            if (manager != null) manager.clear(target);
            completedDownload = false;
        }
    }

    private void updateDegree() {
        degree = (degree + 270) % 360;
        resetAutoUpgradeState();
        loadImage();
    }

    public void rotate() {
        updateDegree();
    }

    @Override
    public void onDestroyView() {
        cancelRequest();
        if (photoView != null) photoView.setImageDrawable(null);
        target = null;
        photoView = null;
        retryButton = null;
        super.onDestroyView();
    }

    private float estimateInitialScale() {
        if (originalW > 0 && originalH > 0) {
            return calculateScaleFactor(originalW, originalH);
        }
        return 1f;
    }

    private void maybeUpgradeDecodeForZoom(float currentScale) {
        float scaleHint = clampDecodeScale(currentScale);
        int desiredLevel = qualityLevelForScale(scaleHint);
        if (desiredLevel <= lastQualityLevel) return;
        if (!isAdded() || photoView == null) return;
        if (!Looper.getMainLooper().isCurrentThread()) return;
        int[] decodeSize = computeDecodeSizePx(scaleHint, oversampleForLevel(desiredLevel));
        if (decodeSize[0] == lastRequestedW && decodeSize[1] == lastRequestedH && lastRequestedDegree == degree) {
            lastQualityLevel = desiredLevel;
            return;
        }
        requestedQualityLevel = desiredLevel;
        loadImage(Priority.IMMEDIATE);
    }

    private static int qualityLevelForScale(float scale) {
        if (scale >= 3.0f) return 3;
        if (scale >= 2.0f) return 2;
        return 1;
    }

    private static float oversampleForLevel(int level) {
        switch (level) {
            case 3:
                return 1.15f;
            case 2:
                return 1.10f;
            default:
                return 1.15f;
        }
    }

    private static float clampDecodeScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return 1f;
        return Math.max(1f, Math.min(3f, scale));
    }

    private int[] computeDecodeSizePx(float scaleHint, float oversample) {
        FragmentActivity activity = getActivity();
        int viewportW = photoView != null ? photoView.getWidth() : 0;
        int viewportH = photoView != null ? photoView.getHeight() : 0;
        if (viewportW <= 0) viewportW = Global.getDeviceWidth(activity);
        if (viewportH <= 0) viewportH = Global.getDeviceHeight(activity);

        float decodeScale = clampDecodeScale(scaleHint) * Math.max(1f, oversample);
        boolean swapForRotation = degree == 90 || degree == 270;

        int effectiveOriginalW = originalW;
        int effectiveOriginalH = originalH;
        if (swapForRotation) {
            int tmp = effectiveOriginalW;
            effectiveOriginalW = effectiveOriginalH;
            effectiveOriginalH = tmp;
        }

        int targetW;
        int targetH;
        if (effectiveOriginalW > 0 && effectiveOriginalH > 0) {
            float baseFitScale = Math.min(viewportW / (float) effectiveOriginalW, viewportH / (float) effectiveOriginalH);
            baseFitScale = Math.max(0.01f, baseFitScale);
            float desiredScale = baseFitScale * decodeScale;
            targetW = Math.max(1, (int) Math.ceil(effectiveOriginalW * desiredScale));
            targetH = Math.max(1, (int) Math.ceil(effectiveOriginalH * desiredScale));
            targetW = Math.min(targetW, effectiveOriginalW);
            targetH = Math.min(targetH, effectiveOriginalH);
        } else {
            targetW = Math.max(1, Math.round(viewportW * decodeScale));
            targetH = Math.max(1, Math.round(viewportH * decodeScale));
        }

        long pixels = (long) targetW * (long) targetH;
        if (pixels > MAX_DECODE_PIXELS) {
            double shrink = Math.sqrt((double) MAX_DECODE_PIXELS / (double) pixels);
            targetW = Math.max(1, (int) Math.floor(targetW * shrink));
            targetH = Math.max(1, (int) Math.floor(targetH * shrink));
        }
        return new int[]{targetW, targetH};
    }

    private void resetAutoUpgradeState() {
        autoUpgradeAttempted = false;
        autoUpgradeInFlight = false;
        autoUpgradeKey = null;
        autoUpgradePreservedDisplayMatrix = null;
        preserveDisplayMatrixDuringAutoUpgrade = false;
        autoUpgradeRequestGeneration = -1;
    }

    private void scheduleAutoUpgradeCheck(int generation) {
        if (!isAdded() || photoView == null) return;
        photoView.post(() -> {
            if (!isAdded() || photoView == null) return;
            if (generation != requestGeneration) return;
            maybeAutoUpgradeToSharpOnce();
        });
    }

    private void maybeAutoUpgradeToSharpOnce() {
        if (autoUpgradeAttempted || autoUpgradeInFlight) return;
        if (photoView == null) return;
        Drawable drawable = photoView.getDrawable();
        if (drawable == null) return;
        if (Math.abs(photoView.getScale() - baseScale) > 0.01f) return;

        String key = currentAutoUpgradeKey();
        if (autoUpgradeKey != null && autoUpgradeKey.equals(key)) return;

        int[] decodedSize = getDecodedDrawableSize(drawable);
        if (decodedSize == null || decodedSize[0] <= 0 || decodedSize[1] <= 0) return;

        float[] displayedSize = getDisplayedSizePx(photoView, decodedSize[0], decodedSize[1]);
        if (displayedSize == null) return;

        boolean isUpscaled =
            decodedSize[0] < displayedSize[0] * UPSCALE_TOLERANCE ||
                decodedSize[1] < displayedSize[1] * UPSCALE_TOLERANCE;
        if (!isUpscaled) {
            autoUpgradeAttempted = true;
            autoUpgradeKey = key;
            return;
        }

        int targetW = (int) Math.ceil(Math.max(displayedSize[0] * AUTO_UPGRADE_DISPLAY_OVERSAMPLE, decodedSize[0] * 2f));
        int targetH = (int) Math.ceil(Math.max(displayedSize[1] * AUTO_UPGRADE_DISPLAY_OVERSAMPLE, decodedSize[1] * 2f));
        int[] capped = capDecodeSizePx(targetW, targetH);
        if (capped[0] == lastRequestedW && capped[1] == lastRequestedH && lastRequestedDegree == degree) {
            autoUpgradeAttempted = true;
            autoUpgradeKey = key;
            return;
        }

        autoUpgradeAttempted = true;
        autoUpgradeInFlight = true;
        autoUpgradeKey = key;
        Matrix preserved = new Matrix();
        photoView.getSuppMatrix(preserved);
        autoUpgradePreservedDisplayMatrix = preserved;
        preserveDisplayMatrixDuringAutoUpgrade = true;
        loadImageWithOverride(Priority.IMMEDIATE, capped[0], capped[1]);
    }

    private String currentAutoUpgradeKey() {
        String model = pageFile != null ? pageFile.getAbsolutePath() : (url != null ? url.toString() : "null");
        return model + "|" + degree;
    }

    @Nullable
    private static int[] getDecodedDrawableSize(@NonNull Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return new int[]{bitmapDrawable.getBitmap().getWidth(), bitmapDrawable.getBitmap().getHeight()};
            }
        }
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return null;
        return new int[]{w, h};
    }

    @Nullable
    private static float[] getDisplayedSizePx(@NonNull ImageView imageView, int intrinsicW, int intrinsicH) {
        if (intrinsicW <= 0 || intrinsicH <= 0) return null;
        Matrix matrix = imageView.getImageMatrix();
        if (matrix == null) return null;
        RectF rect = new RectF(0f, 0f, (float) intrinsicW, (float) intrinsicH);
        matrix.mapRect(rect);
        float displayedW = Math.abs(rect.width());
        float displayedH = Math.abs(rect.height());
        if (displayedW <= 0f || displayedH <= 0f) return null;
        return new float[]{displayedW, displayedH};
    }

    private int[] capDecodeSizePx(int targetW, int targetH) {
        int effectiveOriginalW = originalW;
        int effectiveOriginalH = originalH;
        boolean swapForRotation = degree == 90 || degree == 270;
        if (swapForRotation) {
            int tmp = effectiveOriginalW;
            effectiveOriginalW = effectiveOriginalH;
            effectiveOriginalH = tmp;
        }
        if (effectiveOriginalW > 0 && effectiveOriginalH > 0) {
            targetW = Math.min(targetW, effectiveOriginalW);
            targetH = Math.min(targetH, effectiveOriginalH);
        }
        targetW = Math.max(1, targetW);
        targetH = Math.max(1, targetH);

        long pixels = (long) targetW * (long) targetH;
        if (pixels > MAX_DECODE_PIXELS) {
            double shrink = Math.sqrt((double) MAX_DECODE_PIXELS / (double) pixels);
            targetW = Math.max(1, (int) Math.floor(targetW * shrink));
            targetH = Math.max(1, (int) Math.floor(targetH * shrink));
        }
        return new int[]{targetW, targetH};
    }

    private void loadImageWithOverride(Priority priority, int overrideW, int overrideH) {
        if (photoView == null) return;
        RequestBuilder<Drawable> request = loadPage();
        if (request == null) return;

        requestGeneration++;
        autoUpgradeRequestGeneration = requestGeneration;
        completedDownload = false;
        lastRequestedDegree = degree;
        lastRequestedW = overrideW;
        lastRequestedH = overrideH;

        Drawable current = photoView.getDrawable();
        RequestOptions options = new RequestOptions()
            .fitCenter()
            .downsample(DownsampleStrategy.FIT_CENTER)
            .format(DecodeFormat.PREFER_ARGB_8888)
            .override(overrideW, overrideH);

        RequestBuilder<Drawable> builder = request
            .transform(new Rotate(degree))
            .apply(options);
        if (current != null) {
            builder = builder.placeholder(current);
        } else {
            builder = builder.placeholder(R.drawable.ic_launcher_foreground);
        }
        builder
            .error(R.drawable.ic_refresh)
            .priority(priority)
            .addListener(new RequestListener<>() {
                @Override
                public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                    completedDownload = false;
                    autoUpgradeInFlight = false;
                    return false;
                }

                @Override
                public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                    completedDownload = true;
                    autoUpgradeInFlight = false;
                    return false;
                }
            })
            .into(this.target);
    }
}
