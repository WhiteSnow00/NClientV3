package com.maxwai.nclientv3.api;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GalleryData;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.api.components.Page;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.components.TagList;
import com.maxwai.nclientv3.api.enums.ImageExt;
import com.maxwai.nclientv3.api.enums.Language;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.classes.Size;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Element;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

public class SimpleGallery extends GenericGallery {
    public static final Creator<SimpleGallery> CREATOR = new Creator<>() {
        @Override
        public SimpleGallery createFromParcel(Parcel in) {
            return new SimpleGallery(in);
        }

        @Override
        public SimpleGallery[] newArray(int size) {
            return new SimpleGallery[size];
        }
    };
    private final String title;
    private final ImageExt thumbnail;
    private final int id, mediaId;
    private Language language = Language.UNKNOWN;
    private TagList tags;

    public SimpleGallery(Parcel in) {
        title = in.readString();
        id = in.readInt();
        mediaId = in.readInt();
        thumbnail = ImageExt.values()[in.readByte()];
        language = Language.values()[in.readByte()];
    }

    public boolean hasTags(Collection<Tag> tags) {
        return this.tags != null && this.tags.hasTags(tags);
    }

    @SuppressLint("Range")
    public SimpleGallery(Cursor c) {
        title = c.getString(c.getColumnIndex(Queries.HistoryTable.TITLE));
        id = c.getInt(c.getColumnIndex(Queries.HistoryTable.ID));
        mediaId = c.getInt(c.getColumnIndex(Queries.HistoryTable.MEDIAID));
        thumbnail = ImageExt.values()[c.getInt(c.getColumnIndex(Queries.HistoryTable.THUMB))];
    }

    public SimpleGallery(Context context, Element e) {
        String temp;
        String tags = e.attr("data-tags").replace(' ', ',');
        this.tags = Queries.TagTable.getTagsFromListOfInt(tags);
        language = Gallery.loadLanguage(this.tags);
        Element a = e.getElementsByTag("a").first();
        temp = Objects.requireNonNull(a).attr("href");
        id = Integer.parseInt(temp.substring(3, temp.length() - 1));
        a = e.getElementsByTag("img").first();
        temp = Objects.requireNonNull(a).hasAttr("data-src") ? a.attr("data-src") : a.attr("src");
        mediaId = Integer.parseInt(temp.substring(temp.indexOf("galleries") + 10, temp.lastIndexOf('/')));
        String extension = temp.substring(temp.indexOf('.', temp.lastIndexOf('/')) + 1);
        thumbnail = Page.stringToExt(extension);
        title = Objects.requireNonNull(e.getElementsByTag("div").first()).text();
        if (context != null && id > Global.getMaxId()) Global.updateMaxId(context, id);
    }

    private SimpleGallery(String title, int id, int mediaId, ImageExt thumbnail, Language language, TagList tags) {
        this.title = title;
        this.id = id;
        this.mediaId = mediaId;
        this.thumbnail = thumbnail == null ? ImageExt.JPG : thumbnail;
        this.language = language == null ? Language.UNKNOWN : language;
        this.tags = tags;
    }

    public SimpleGallery(Gallery gallery) {
        title = gallery.getTitle();
        mediaId = gallery.getMediaId();
        id = gallery.getId();
        thumbnail = gallery.getThumb();
    }

    public static SimpleGallery fromV2ListItem(Context context, JSONObject json) {
        String thumbnailPath = resolveThumbnailPath(json);
        int galleryId = optInt(json, "id");
        int mediaId = optInt(json, "media_id");
        if (mediaId <= 0) mediaId = parseMediaId(thumbnailPath);

        TagList tagList = parseTagList(json);
        Language language = Gallery.loadLanguage(tagList);
        String title = parseTitle(json);
        ImageExt thumbExt = parseThumbnailExtension(thumbnailPath);

        if (context != null && galleryId > Global.getMaxId()) {
            Global.updateMaxId(context, galleryId);
        }

        return new SimpleGallery(title, galleryId, mediaId, thumbExt, language, tagList);
    }

    private static TagList parseTagList(JSONObject json) {
        JSONArray tagIds = json.optJSONArray("tag_ids");
        if (tagIds == null) {
            tagIds = new JSONArray();
            JSONArray tags = json.optJSONArray("tags");
            if (tags != null) {
                for (int i = 0; i < tags.length(); i++) {
                    JSONObject tag = tags.optJSONObject(i);
                    if (tag == null) continue;
                    int tagId = optInt(tag, "id");
                    if (tagId > 0) tagIds.put(tagId);
                }
            }
        }

        if (tagIds.length() == 0) return new TagList();

        StringBuilder ids = new StringBuilder(tagIds.length() * 6);
        for (int i = 0; i < tagIds.length(); i++) {
            int tagId = optInt(tagIds, i);
            if (tagId <= 0) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(tagId);
        }
        if (ids.length() == 0) return new TagList();
        return Queries.TagTable.getTagsFromListOfInt(ids.toString());
    }

    private static String parseTitle(JSONObject json) {
        String[] keys = {"english_title", "pretty_title", "title", "japanese_title"};
        for (String key : keys) {
            String resolved = json.optString(key);
            if (!resolved.isEmpty()) return resolved;
        }
        JSONObject title = json.optJSONObject("title");
        if (title != null) {
            String[] titleKeys = {"english", "pretty", "japanese"};
            for (String key : titleKeys) {
                String resolved = title.optString(key);
                if (!resolved.isEmpty()) return resolved;
            }
        }
        return "Unnamed";
    }

    private static String resolveThumbnailPath(JSONObject json) {
        String path = extractPath(json.opt("thumbnail"));
        if (path.isEmpty()) path = extractPath(json.opt("cover"));
        if (path.isEmpty()) path = json.optString("thumbnail_path");
        if (path.isEmpty()) path = json.optString("cover_path");
        if (!path.isEmpty()) return path;

        JSONObject images = json.optJSONObject("images");
        if (images == null) return "";

        path = extractPath(images.opt("thumbnail"));
        if (path.isEmpty()) path = extractPath(images.opt("cover"));
        if (path.isEmpty()) path = images.optString("thumbnail_path");
        if (path.isEmpty()) path = images.optString("cover_path");
        return path;
    }

    private static String extractPath(Object value) {
        if (value instanceof String) return (String) value;
        if (!(value instanceof JSONObject)) return "";

        JSONObject object = (JSONObject) value;
        String path = object.optString("path");
        if (path.isEmpty()) path = object.optString("url");
        if (path.isEmpty()) path = object.optString("src");
        return path;
    }

    private static int parseMediaId(String thumbnailPath) {
        if (thumbnailPath == null || thumbnailPath.isEmpty()) return 0;
        int galleriesIndex = thumbnailPath.indexOf("/galleries/");
        if (galleriesIndex < 0) return 0;
        galleriesIndex += "/galleries/".length();
        int end = thumbnailPath.indexOf('/', galleriesIndex);
        if (end < 0) end = thumbnailPath.length();
        try {
            return Integer.parseInt(thumbnailPath.substring(galleriesIndex, end));
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    private static ImageExt parseThumbnailExtension(String thumbnailPath) {
        if (thumbnailPath == null || thumbnailPath.isEmpty()) return ImageExt.JPG;
        int fileStart = thumbnailPath.lastIndexOf('/') + 1;
        String filename = thumbnailPath.substring(fileStart);
        if (filename.startsWith("thumb.")) filename = filename.substring("thumb.".length());
        ImageExt ext = Page.stringToExt(filename);
        if (ext != null) return ext;

        int dotIndex = thumbnailPath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex >= thumbnailPath.length() - 1) return ImageExt.JPG;
        ext = Page.stringToExt(thumbnailPath.substring(dotIndex + 1));
        return ext == null ? ImageExt.JPG : ext;
    }

    private static int optInt(JSONObject json, String key) {
        Object value = json.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignore) {
                return 0;
            }
        }
        return 0;
    }

    private static int optInt(JSONArray jsonArray, int index) {
        Object value = jsonArray.opt(index);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignore) {
                return 0;
            }
        }
        return 0;
    }

    private static String extToString(ImageExt ext) {
        if (ext == null) {
            return null;
        }
        return ext.getName();
    }

    public Language getLanguage() {
        return language;
    }

    public boolean hasIgnoredTags(String s) {
        if (tags == null) return false;
        for (Tag t : tags.getAllTagsList())
            if (s.contains(t.toQueryTag(TagStatus.AVOIDED))) {
                LogUtility.d("Found: " + s + ",," + t.toQueryTag());
                return true;
            }
        return false;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public Type getType() {
        return Type.SIMPLE;
    }

    @Override
    public int getPageCount() {
        return 0;
    }

    @Override
    public boolean isValid() {
        return id > 0;
    }

    @Override
    @NonNull
    public String getTitle() {
        return title;
    }

    @Override
    public Size getMaxSize() {
        return null;
    }

    @Override
    public Size getMinSize() {
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title);
        dest.writeInt(id);
        dest.writeInt(mediaId);
        dest.writeByte((byte) thumbnail.ordinal());
        dest.writeByte((byte) language.ordinal());
        //TAGS AREN'T WRITTEN
    }

    public Uri getThumbnail() {
        if (thumbnail == ImageExt.GIF) {
            return Uri.parse(String.format(Locale.US, "https://i1." + Utility.getHost() + "/galleries/%d/1.gif", mediaId));
        }
        return Uri.parse(String.format(Locale.US, "https://t1." + Utility.getHost() + "/galleries/%d/thumb.%s", mediaId, extToString(thumbnail)));
    }

    public int getMediaId() {
        return mediaId;
    }

    public ImageExt getThumb() {
        return thumbnail;
    }

    @Override
    public GalleryFolder getGalleryFolder() {
        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return "SimpleGallery{" +
            "language=" + language +
            ", title='" + title + '\'' +
            ", thumbnail=" + thumbnail +
            ", id=" + id +
            ", mediaId=" + mediaId +
            '}';
    }

    @Override
    public boolean hasGalleryData() {
        return false;
    }

    @Override
    public GalleryData getGalleryData() {
        return null;
    }
}
