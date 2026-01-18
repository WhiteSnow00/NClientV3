package com.maxwai.nclientv3.components.views;

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
    // Cap decoded pixel count per page to avoid OOMs when users zoom; 8MP ~= 32MB in ARGB_8888.
    private static final long MAX_DECODE_PIXELS = 8_000_000L;
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


    public ZoomFragment() {
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory) {
        Bundle args = new Bundle();
        args.putString("URL", gallery.isLocal() ? null : ((Gallery) gallery).getPageUrl(page).toString());
        args.putParcelable("FOLDER", directory == null ? null : directory.getPage(page + 1));
        try {
            // Preserve zoom behavior when downsampling: use original page dimensions for scale decisions.
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
        //find views
        photoView = rootView.findViewById(R.id.image);
        retryButton = rootView.findViewById(R.id.imageView);
        //read arguments
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
                if (toShow instanceof PhotoView)
                    scalePhoto(drawable);
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
            }

            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                applyDrawable(photoView, retryButton, resource);
                if (resource instanceof Animatable)
                    ((GifDrawable) resource).start();
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
        photoView.setScale(calculateScaleFactor(w, h), 0, 0, false);
    }

    public void loadImage() {
        loadImage(Priority.NORMAL);
    }

    public void loadImage(Priority priority) {
        if (photoView == null) return;
        int qualityLevel = requestedQualityLevel > 0 ? requestedQualityLevel : qualityLevelForScale(estimateInitialScale());
        int[] decodeSize = computeDecodeSizePx(qualityScaleForLevel(qualityLevel));
        if (completedDownload
            && lastRequestedDegree == degree
            && lastRequestedW == decodeSize[0]
            && lastRequestedH == decodeSize[1]) {
            return;
        }
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
                .downsample(DownsampleStrategy.AT_MOST)
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
            // Allow re-requesting if the page becomes visible again; also releases decoded bitmaps for far pages.
            completedDownload = false;
        }
    }

    private void updateDegree() {
        degree = (degree + 270) % 360;
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
        // Only upgrade on a "significant" zoom to avoid repeated reloads while pinching.
        int desiredLevel = qualityLevelForScale(currentScale);
        if (desiredLevel <= lastQualityLevel) return;
        if (!isAdded() || photoView == null) return;
        // Avoid doing Glide work off the main thread.
        if (!Looper.getMainLooper().isCurrentThread()) return;
        int[] decodeSize = computeDecodeSizePx(qualityScaleForLevel(desiredLevel));
        if (decodeSize[0] == lastRequestedW && decodeSize[1] == lastRequestedH && lastRequestedDegree == degree) {
            lastQualityLevel = desiredLevel;
            return;
        }
        requestedQualityLevel = desiredLevel;
        // Force a reload with a larger decode target; only 1 bitmap is retained per fragment.
        loadImage(Priority.IMMEDIATE);
    }

    private static int qualityLevelForScale(float scale) {
        if (scale >= 3.0f) return 3;
        if (scale >= 2.0f) return 2;
        return 1;
    }

    private static float qualityScaleForLevel(int level) {
        switch (level) {
            case 3:
                return 2.5f;
            case 2:
                return 1.75f;
            default:
                return 1.0f;
        }
    }

    private int[] computeDecodeSizePx(float qualityScale) {
        FragmentActivity activity = getActivity();
        int viewportW = photoView != null ? photoView.getWidth() : 0;
        int viewportH = photoView != null ? photoView.getHeight() : 0;
        if (viewportW <= 0) viewportW = Global.getDeviceWidth(activity);
        if (viewportH <= 0) viewportH = Global.getDeviceHeight(activity);

        int targetW;
        int targetH;
        if (originalW > 0 && originalH > 0) {
            float fitScale = Math.min((viewportW * qualityScale) / (float) originalW, (viewportH * qualityScale) / (float) originalH);
            fitScale = Math.max(0.01f, fitScale);
            targetW = Math.max(1, (int) Math.ceil(originalW * fitScale));
            targetH = Math.max(1, (int) Math.ceil(originalH * fitScale));
            targetW = Math.min(targetW, originalW);
            targetH = Math.min(targetH, originalH);
        } else {
            targetW = Math.max(1, Math.round(viewportW * qualityScale));
            targetH = Math.max(1, Math.round(viewportH * qualityScale));
        }

        long pixels = (long) targetW * (long) targetH;
        if (pixels > MAX_DECODE_PIXELS) {
            double shrink = Math.sqrt((double) MAX_DECODE_PIXELS / (double) pixels);
            targetW = Math.max(1, (int) Math.floor(targetW * shrink));
            targetH = Math.max(1, (int) Math.floor(targetH * shrink));
        }
        return new int[]{targetW, targetH};
    }
}
