package com.maxwai.nclientv3.utility;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.media.MediaScannerConnection;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.settings.Global;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class Utility {
    public static final Random RANDOM = new Random(System.nanoTime());
    public static final String ORIGINAL_URL = "nhentai.net";
    public static final String PROTOCOL = "https://";

    public static final String SINGLE_IMAGE_SAVE_LOCATION_DOWNLOADS = "downloads_nclientv3";
    public static final String SINGLE_IMAGE_SAVE_LOCATION_APP_PRIVATE = "app_private";
    public static final String SINGLE_IMAGE_SAVE_LOCATION_SAF_CUSTOM = "saf_custom";

    public static String getBaseUrl() {
        return "https://" + Utility.getHost() + "/";
    }


    public static String getHost() {
        return Global.getMirror();
    }

    private static void parseEscapedCharacter(Reader reader, Writer writer) throws IOException {
        int toCreate, read;
        switch (read = reader.read()) {
            case 'u':
                toCreate = 0;
                for (int i = 0; i < 4; i++) {
                    toCreate *= 16;
                    toCreate += Character.digit(reader.read(), 16);
                }
                writer.write(toCreate);
                break;
            case 'n':
                writer.write('\n');
                break;
            case 't':
                writer.write('\t');
                break;
            default:
                writer.write('\\');
                writer.write(read);
                break;
        }
    }

    @NonNull
    public static String unescapeUnicodeString(@Nullable String scriptHtml) {
        if (scriptHtml == null) return "";
        StringReader reader = new StringReader(scriptHtml);
        StringWriter writer = new StringWriter();
        int actualChar;
        try {
            while ((actualChar = reader.read()) != -1) {
                if (actualChar != '\\') writer.write(actualChar);
                else parseEscapedCharacter(reader, writer);
            }
        } catch (IOException ignore) {
            return "";
        }
        return writer.toString();
    }

    public static void threadSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            LogUtility.d("Unimportant sleep interrupted", e);
        }
    }

    public static void tintMenu(Context context, Menu menu) {
        int x = menu.size();
        for (int i = 0; i < x; i++) {
            MenuItem item = menu.getItem(i);
            Global.setTint(context, item.getIcon());
        }
    }

    @Nullable
    private static Bitmap drawableToBitmap(Drawable dra) {
        if (!(dra instanceof BitmapDrawable)) return null;
        return ((BitmapDrawable) dra).getBitmap();
    }

    public static void saveImage(Drawable drawable, File output) {
        Bitmap b = drawableToBitmap(drawable);
        if (b != null) saveImage(b, output);
    }

    public interface SingleImageSaveCallback {
        void onComplete(boolean success, @NonNull String message);
    }

    @NonNull
    public static String getSingleImageSaveLocation(@NonNull Context context) {
        SharedPreferences preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        String key = context.getString(R.string.preference_key_single_image_save_location);
        String value = preferences.getString(key, SINGLE_IMAGE_SAVE_LOCATION_DOWNLOADS);
        return value == null ? SINGLE_IMAGE_SAVE_LOCATION_DOWNLOADS : value;
    }

    public static boolean isSingleImageSaveLocationSafCustom(@NonNull Context context) {
        return SINGLE_IMAGE_SAVE_LOCATION_SAF_CUSTOM.equals(getSingleImageSaveLocation(context));
    }

    @Nullable
    private static Uri getSingleImageSaveTreeUri(@NonNull Context context) {
        SharedPreferences preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        String key = context.getString(R.string.preference_key_single_image_save_tree_uri);
        String uriString = preferences.getString(key, null);
        if (uriString == null || uriString.trim().isEmpty()) return null;
        try {
            return Uri.parse(uriString);
        } catch (Exception ignore) {
            return null;
        }
    }

    @NonNull
    private static String getSingleImageSaveTreeName(@NonNull Context context) {
        SharedPreferences preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        String key = context.getString(R.string.preference_key_single_image_save_tree_name);
        String name = preferences.getString(key, null);
        if (name == null || name.trim().isEmpty()) return context.getString(R.string.save_images_location_custom_folder);
        return name.trim();
    }

    private interface OutputStreamConsumer {
        void writeTo(@NonNull OutputStream os) throws IOException;
    }

    public static void saveSingleImageAsync(
        @NonNull Context context,
        @Nullable Drawable drawable,
        @Nullable File sourceFile,
        int galleryId,
        int pageNumber1Based,
        @NonNull SingleImageSaveCallback callback
    ) {
        Context appContext = context.getApplicationContext();
        AppExecutors.io().execute(() -> {
            SaveOutcome outcome = saveSingleImageInternal(appContext, drawable, sourceFile, galleryId, pageNumber1Based);
            ContextCompat.getMainExecutor(appContext).execute(() -> callback.onComplete(outcome.success, outcome.message));
        });
    }

    private static class SaveOutcome {
        final boolean success;
        @NonNull
        final String message;

        SaveOutcome(boolean success, @NonNull String message) {
            this.success = success;
            this.message = message;
        }
    }

    private static SaveOutcome saveSingleImageInternal(
        @NonNull Context context,
        @Nullable Drawable drawable,
        @Nullable File sourceFile,
        int galleryId,
        int pageNumber1Based
    ) {
        int safeGalleryId = Math.max(0, galleryId);
        int safePage = Math.max(1, pageNumber1Based);

        String extension = getFileExtension(sourceFile);
        if (extension == null) extension = "jpg";
        String mimeType = mimeTypeForExtension(extension);
        if (mimeType == null) {
            extension = "jpg";
            mimeType = "image/jpeg";
        }

        String displayName = String.format(
            Locale.US,
            "NClientV3_%d_%03d_%d.%s",
            safeGalleryId,
            safePage,
            System.currentTimeMillis(),
            extension
        );

        OutputStreamConsumer writer;
        if (sourceFile != null && sourceFile.isFile()) {
            writer = os -> copyFileToStream(sourceFile, os);
        } else {
            Bitmap bitmap = drawable == null ? null : drawableToBitmap(drawable);
            if (bitmap == null) {
                return new SaveOutcome(false, context.getString(R.string.save_images_failed_to_save, context.getString(R.string.failed)));
            }
            Bitmap.CompressFormat format = "png".equalsIgnoreCase(extension) ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
            int quality = format == Bitmap.CompressFormat.PNG ? 100 : 95;
            Bitmap finalBitmap = bitmap;
            Bitmap.CompressFormat finalFormat = format;
            int finalQuality = quality;
            writer = os -> {
                if (!finalBitmap.compress(finalFormat, finalQuality, os)) {
                    throw new IOException("Bitmap compress failed");
                }
                os.flush();
            };
        }

        String location = getSingleImageSaveLocation(context);
        try {
            switch (location) {
                case SINGLE_IMAGE_SAVE_LOCATION_APP_PRIVATE: {
                    Global.initStorage(context);
                    File dir = Global.SCREENFOLDER;
                    if (dir == null) {
                        return new SaveOutcome(false, context.getString(R.string.save_images_failed_to_save, "Storage not available"));
                    }
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File out = new File(dir, displayName);
                    try (OutputStream os = new FileOutputStream(out)) {
                        writer.writeTo(os);
                    }
                    return new SaveOutcome(true, context.getString(R.string.save_images_saved_to, out.getAbsolutePath()));
                }
                case SINGLE_IMAGE_SAVE_LOCATION_SAF_CUSTOM: {
                    Uri treeUri = getSingleImageSaveTreeUri(context);
                    if (treeUri == null) {
                        return new SaveOutcome(false, context.getString(R.string.save_images_custom_folder_not_set));
                    }
                    ContentResolver resolver = context.getContentResolver();
                    String docId = DocumentsContract.getTreeDocumentId(treeUri);
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    Uri outUri = DocumentsContract.createDocument(resolver, parent, mimeType, displayName);
                    if (outUri == null) {
                        return new SaveOutcome(false, context.getString(R.string.save_images_failed_to_save, "Unable to create file"));
                    }
                    try (OutputStream os = resolver.openOutputStream(outUri, "w")) {
                        if (os == null) throw new IOException("Unable to open output stream");
                        writer.writeTo(os);
                    }
                    return new SaveOutcome(true, context.getString(R.string.save_images_saved_to, getSingleImageSaveTreeName(context)));
                }
                case SINGLE_IMAGE_SAVE_LOCATION_DOWNLOADS:
                default:
                    return saveToPublicDownloads(context, displayName, mimeType, writer);
            }
        } catch (IOException e) {
            LogUtility.e("Error saving image", e);
            String message = e.getLocalizedMessage() == null ? context.getString(R.string.failed) : e.getLocalizedMessage();
            return new SaveOutcome(false, context.getString(R.string.save_images_failed_to_save, message));
        } catch (SecurityException e) {
            LogUtility.e("Missing permission saving image", e);
            return new SaveOutcome(false, context.getString(R.string.save_images_failed_to_save, "Permission denied"));
        }
    }

    private static SaveOutcome saveToPublicDownloads(
        @NonNull Context context,
        @NonNull String displayName,
        @NonNull String mimeType,
        @NonNull OutputStreamConsumer writer
    ) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NClientV3");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return new SaveOutcome(false, context.getString(R.string.save_images_failed_to_save, "MediaStore insert failed"));
            try (OutputStream os = resolver.openOutputStream(uri, "w")) {
                if (os == null) throw new IOException("Unable to open output stream");
                writer.writeTo(os);
            } finally {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            }
            return new SaveOutcome(true, context.getString(R.string.save_images_saved_to, context.getString(R.string.save_images_location_downloads)));
        }

        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, "NClientV3");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, displayName);
        try (OutputStream os = new FileOutputStream(out)) {
            writer.writeTo(os);
        }
        MediaScannerConnection.scanFile(context, new String[]{out.getAbsolutePath()}, new String[]{mimeType}, null);
        return new SaveOutcome(true, context.getString(R.string.save_images_saved_to, out.getAbsolutePath()));
    }

    @Nullable
    private static String getFileExtension(@Nullable File file) {
        if (file == null) return null;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    @Nullable
    private static String mimeTypeForExtension(@NonNull String ext) {
        switch (ext.toLowerCase(Locale.US)) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            default:
                return null;
        }
    }

    private static void copyFileToStream(@NonNull File sourceFile, @NonNull OutputStream outputStream) throws IOException {
        try (InputStream inputStream = new FileInputStream(sourceFile)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        }
    }

    /**
     * Offloads bitmap compression + file writes to a background thread to avoid UI stalls/ANRs.
     * The drawable-to-bitmap cast is performed on the caller thread (cheap), while compression happens on IO.
     */
    public static void saveImageAsync(@NonNull Context context, @Nullable Drawable drawable, @NonNull File output) {
        Bitmap b = drawable == null ? null : drawableToBitmap(drawable);
        if (b == null) return;
        AppExecutors.io().execute(() -> saveImage(b, output));
    }

    private static void saveImage(@NonNull Bitmap bitmap, @NonNull File output) {
        try {
            if (!output.exists())
                //noinspection ResultOfMethodCallIgnored
                output.createNewFile();
            try (FileOutputStream ostream = new FileOutputStream(output)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, ostream);
                ostream.flush();
            }
        } catch (IOException e) {
            LogUtility.e(e.getLocalizedMessage(), e);
        }
    }

    public static long writeStreamToFile(InputStream inputStream, File filePath) throws IOException {
        try (inputStream;
             FileOutputStream outputStream = new FileOutputStream(filePath)) {
            int read;
            long totalByte = 0;
            byte[] bytes = new byte[1024];
            while ((read = inputStream.read(bytes)) != -1) {
                outputStream.write(bytes, 0, read);
                totalByte += read;
            }
            outputStream.flush();
            return totalByte;
        }
    }

    public static void sendImage(Context context, Drawable drawable, String text) {
        context = context.getApplicationContext();
        try {
            File tempFile = File.createTempFile("toSend", ".jpg", context.getCacheDir());
            tempFile.deleteOnExit();
            Bitmap image = drawableToBitmap(drawable);
            if (image == null) return;
            saveImage(image, tempFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            if (text != null) shareIntent.putExtra(Intent.EXTRA_TEXT, text);
            Uri x = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", tempFile);
            shareIntent.putExtra(Intent.EXTRA_STREAM, x);
            shareIntent.setType("image/jpeg");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            List<ResolveInfo> resInfoList = context.getPackageManager().queryIntentActivities(shareIntent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                context.grantUriPermission(packageName, x, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            shareIntent = Intent.createChooser(shareIntent, context.getString(R.string.share_with));
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(shareIntent);
        } catch (IOException e) {
            LogUtility.e("Error creating temp file", e);
            Toast.makeText(context, R.string.send_image_error, Toast.LENGTH_SHORT).show();
        }

    }

}
