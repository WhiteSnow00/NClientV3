package com.maxwai.nclientv3.api.local;

import com.maxwai.nclientv3.LocalActivity;
import com.maxwai.nclientv3.adapters.LocalAdapter;
import com.maxwai.nclientv3.components.ThreadAsyncTask;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public class FakeInspector extends ThreadAsyncTask<LocalActivity, LocalActivity, LocalActivity> {
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private final ArrayList<LocalGallery> galleries;
    private final ArrayList<String> invalidPaths;
    private final File folder;
    private final LocalAdapter localAdapter;
    private final boolean forceRefresh;

    private boolean didScan = false;
    private boolean shouldUpdateAdapter = false;
    private long resultSignature = 0L;
    private ArrayList<LocalGallery> resultGalleries = null;

    public FakeInspector(LocalActivity activity, File folder, LocalAdapter localAdapter, boolean forceRefresh) {
        super(activity);
        this.folder = new File(folder, "Download");
        this.localAdapter = localAdapter;
        this.forceRefresh = forceRefresh;
        galleries = new ArrayList<>();
        invalidPaths = new ArrayList<>();
    }

    public static CachedLocalGalleries getCached(File folder) {
        File download = new File(folder, "Download");
        CacheEntry entry = CACHE.get(download.getAbsolutePath());
        if (entry == null) return null;
        return new CachedLocalGalleries(entry.signature, new ArrayList<>(entry.galleries));
    }

    @Override
    protected LocalActivity doInBackground(LocalActivity activity) {
        File parent = this.folder;
        //noinspection ResultOfMethodCallIgnored
        parent.mkdirs();

        long signature = computeSignature(parent);
        resultSignature = signature;
        CacheEntry cached = CACHE.get(parent.getAbsolutePath());
        if (!forceRefresh && cached != null && cached.signature == signature) {
            return activity;
        }

        didScan = true;
        publishProgress(activity);

        File[] files = parent.listFiles();
        if (files == null) {
            resultGalleries = new ArrayList<>();
            shouldUpdateAdapter = true;
            CACHE.put(parent.getAbsolutePath(), new CacheEntry(signature, Collections.emptyList()));
            return activity;
        }

        for (File f : files) if (f.isDirectory()) createGallery(f);
        for (String x : invalidPaths) LogUtility.d("Invalid path: " + x);

        resultGalleries = new ArrayList<>(galleries);
        shouldUpdateAdapter = true;
        CACHE.put(parent.getAbsolutePath(), new CacheEntry(signature, resultGalleries));
        galleries.clear();
        return activity;
    }

    @Override
    protected void onProgressUpdate(LocalActivity values) {
        values.getRefresher().setRefreshing(true);
    }

    @Override
    protected void onPostExecute(LocalActivity activity) {
        activity.getRefresher().setRefreshing(false);
        if (shouldUpdateAdapter && resultGalleries != null) {
            localAdapter.setLocalGalleries(resultGalleries, resultSignature);
        }
    }

    private void createGallery(final File file) {
        LocalGallery lg = new LocalGallery(file, false);
        if (lg.isValid()) {
            galleries.add(lg);
        } else {
            LogUtility.e(lg);
            invalidPaths.add(file.getAbsolutePath());
        }
    }

    private static long computeSignature(File parent) {
        File[] files = parent.listFiles();
        if (files == null) return 0L;
        long count = 0L;
        long maxModified = 0L;
        long sumModified = 0L;
        long nameHash = 1125899906842597L;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            count++;
            long lm = f.lastModified();
            if (lm > maxModified) maxModified = lm;
            sumModified += lm;
            nameHash = nameHash * 31L + f.getName().hashCode();
        }
        long sig = 1469598103934665603L;
        sig = (sig ^ count) * 1099511628211L;
        sig = (sig ^ maxModified) * 1099511628211L;
        sig = (sig ^ sumModified) * 1099511628211L;
        sig = (sig ^ nameHash) * 1099511628211L;
        return sig;
    }

    private static final class CacheEntry {
        final long signature;
        final ArrayList<LocalGallery> galleries;

        CacheEntry(long signature, java.util.List<LocalGallery> galleries) {
            this.signature = signature;
            this.galleries = new ArrayList<>(galleries);
        }
    }

    public static final class CachedLocalGalleries {
        public final long signature;
        public final ArrayList<LocalGallery> galleries;

        CachedLocalGalleries(long signature, ArrayList<LocalGallery> galleries) {
            this.signature = signature;
            this.galleries = galleries;
        }
    }
}
