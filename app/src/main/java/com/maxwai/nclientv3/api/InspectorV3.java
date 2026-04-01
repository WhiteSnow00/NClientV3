package com.maxwai.nclientv3.api;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.api.components.Page;
import com.maxwai.nclientv3.api.components.Ranges;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.ApiRequestType;
import com.maxwai.nclientv3.api.enums.Language;
import com.maxwai.nclientv3.api.enums.SortType;
import com.maxwai.nclientv3.api.enums.SpecialTagIds;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.bypass.BypassNetworkController;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import okhttp3.Request;
import okhttp3.Response;

public class InspectorV3 extends Thread implements Parcelable {
    public static final Creator<InspectorV3> CREATOR = new Creator<>() {
        @Override
        public InspectorV3 createFromParcel(Parcel in) {
            return new InspectorV3(in);
        }

        @Override
        public InspectorV3[] newArray(int size) {
            return new InspectorV3[size];
        }
    };
    private SortType sortType;
    private boolean custom;
    private int page, pageCount = -1, id;
    private String query, url;
    private String browserUrl;
    private ApiRequestType requestType;
    private Set<Tag> tags;
    private ArrayList<GenericGallery> galleries = null;
    private Ranges ranges = null;
    private InspectorResponse response;
    private WeakReference<Context> context;
    private String rawResponse;
    private String responseContentType;

    protected InspectorV3(Parcel in) {
        sortType = SortType.values()[in.readByte()];
        custom = in.readByte() != 0;
        page = in.readInt();
        pageCount = in.readInt();
        id = in.readInt();
        query = in.readString();
        url = in.readString();
        requestType = ApiRequestType.values[in.readByte()];
        List<? extends GenericGallery> tmpList = null;
        switch (GenericGallery.Type.values()[in.readByte()]) {
            case LOCAL:
                tmpList = in.createTypedArrayList(LocalGallery.CREATOR);
                break;
            case SIMPLE:
                tmpList = in.createTypedArrayList(SimpleGallery.CREATOR);
                break;
            case COMPLETE:
                tmpList = in.createTypedArrayList(Gallery.CREATOR);
                break;
        }
        if (tmpList != null)
        {
            galleries = new ArrayList<>();
            galleries.addAll(tmpList);
        }
        tags = new HashSet<>(Objects.requireNonNull(in.createTypedArrayList(Tag.CREATOR)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ranges = in.readParcelable(Ranges.class.getClassLoader(), Ranges.class);
        } else {
            ranges = in.readParcelable(Ranges.class.getClassLoader());
        }
        createUrl();
    }

    private InspectorV3(Context context, InspectorResponse response) {
        initialize(context, response);
    }

    /**
     * This method will not run, but a WebView inside MainActivity will do it in its place
     */
    public static InspectorV3 favoriteInspector(Context context, String query, int page, InspectorResponse response) {
        InspectorV3 inspector = new InspectorV3(context, response);
        inspector.page = page;
        inspector.pageCount = 0;
        inspector.query = query == null ? "" : query;
        inspector.requestType = ApiRequestType.FAVORITE;
        inspector.tags = new HashSet<>(1);
        inspector.createUrl();
        return inspector;
    }

    /**
     * @param favorite true if random online favorite, false for general random manga
     */
    public static InspectorV3 randomInspector(Context context, InspectorResponse response, boolean favorite) {
        InspectorV3 inspector = new InspectorV3(context, response);
        inspector.requestType = favorite ? ApiRequestType.RANDOM_FAVORITE : ApiRequestType.RANDOM;
        inspector.createUrl();
        return inspector;
    }

    public static InspectorV3 galleryInspector(Context context, int id, InspectorResponse response) {
        InspectorV3 inspector = new InspectorV3(context, response);
        inspector.id = id;
        inspector.requestType = ApiRequestType.BYSINGLE;
        inspector.createUrl();
        return inspector;
    }

    public static InspectorV3 basicInspector(Context context, int page, InspectorResponse response) {
        return searchInspector(context, null, null, page, Global.getSortType(), null, response);
    }

    public static InspectorV3 tagInspector(Context context, Tag tag, int page, SortType sortType, InspectorResponse response) {
        Collection<Tag> tags;
        if (!Global.isOnlyTag()) {
            tags = getDefaultTags();
            tags.add(tag);
        } else {
            tags = Collections.singleton(tag);
        }
        return searchInspector(context, null, tags, page, sortType, null, response);
    }

    public static InspectorV3 searchInspector(Context context, String query, Collection<Tag> tags, int page, SortType sortType, @Nullable Ranges ranges, InspectorResponse response) {
        InspectorV3 inspector = new InspectorV3(context, response);
        inspector.custom = tags != null;
        inspector.tags = inspector.custom ? new HashSet<>(tags) : getDefaultTags();
        inspector.tags.addAll(getLanguageTags(Global.getOnlyLanguage()));
        inspector.page = page;
        inspector.pageCount = 0;
        inspector.ranges = ranges;
        inspector.query = query == null ? "" : query;
        inspector.sortType = sortType;
        if (inspector.query.isEmpty() && (ranges == null || ranges.isDefault())) {
            switch (inspector.tags.size()) {
                case 0:
                    inspector.requestType = ApiRequestType.BYALL;
                    inspector.tryByAllPopular();
                    break;
                case 1:
                    inspector.requestType = ApiRequestType.BYTAG;
                    //else by search for the negative tag
                    if (inspector.getTag().getStatus() != TagStatus.AVOIDED)
                        break;
                default:
                    inspector.requestType = ApiRequestType.BYSEARCH;
                    break;
            }
        } else inspector.requestType = ApiRequestType.BYSEARCH;
        inspector.createUrl();
        return inspector;
    }

    @NonNull
    private static HashSet<Tag> getDefaultTags() {
        HashSet<Tag> tags = new HashSet<>(Queries.TagTable.getAllStatus(TagStatus.ACCEPTED));
        tags.addAll(getLanguageTags(Global.getOnlyLanguage()));
        if (Global.removeAvoidedGalleries())
            tags.addAll(Queries.TagTable.getAllStatus(TagStatus.AVOIDED));
        tags.addAll(Queries.TagTable.getAllOnlineBlacklisted());
        return tags;
    }

    private static Set<Tag> getLanguageTags(Language onlyLanguage) {
        Set<Tag> tags = new HashSet<>();
        if (onlyLanguage == null) return tags;
        switch (onlyLanguage) {
            case ENGLISH:
                tags.add(Queries.TagTable.getTagById(SpecialTagIds.LANGUAGE_ENGLISH));
                break;
            case JAPANESE:
                tags.add(Queries.TagTable.getTagById(SpecialTagIds.LANGUAGE_JAPANESE));
                break;
            case CHINESE:
                tags.add(Queries.TagTable.getTagById(SpecialTagIds.LANGUAGE_CHINESE));
                break;
        }
        return tags;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (Objects.requireNonNullElse(sortType, SortType.RECENT_ALL_TIME).ordinal()));
        dest.writeByte((byte) (custom ? 1 : 0));
        dest.writeInt(page);
        dest.writeInt(pageCount);
        dest.writeInt(id);
        dest.writeString(query);
        dest.writeString(url);
        dest.writeByte(requestType.ordinal());
        if (galleries == null || galleries.isEmpty())
            dest.writeByte((byte) GenericGallery.Type.SIMPLE.ordinal());
        else dest.writeByte((byte) galleries.get(0).getType().ordinal());
        dest.writeTypedList(galleries);
        dest.writeTypedList(new ArrayList<>(tags));
        dest.writeParcelable(ranges, flags);
    }

    public String getSearchTitle() {
        //triggered only when in searchMode
        if (!query.isEmpty()) return query;
        String searchQuery = null;
        if (browserUrl != null) searchQuery = Uri.parse(browserUrl).getQueryParameter("q");
        if (searchQuery == null && url != null) searchQuery = Uri.parse(url).getQueryParameter("query");
        return searchQuery == null ? "" : searchQuery.replace('+', ' ');
    }

    public void initialize(Context context, InspectorResponse response) {
        this.response = response;
        this.context = new WeakReference<>(context);
    }

    public InspectorResponse getResponse() {
        return response;
    }

    public InspectorV3 cloneInspector(Context context, InspectorResponse response) {
        InspectorV3 inspectorV3 = new InspectorV3(context, response);
        inspectorV3.query = query;
        inspectorV3.tags = tags;
        inspectorV3.requestType = requestType;
        inspectorV3.sortType = sortType;
        inspectorV3.pageCount = pageCount;
        inspectorV3.page = page;
        inspectorV3.id = id;
        inspectorV3.custom = custom;
        inspectorV3.ranges = ranges;
        inspectorV3.createUrl();
        return inspectorV3;
    }

    private void tryByAllPopular() {
        if (sortType != SortType.RECENT_ALL_TIME) {
            requestType = ApiRequestType.BYSEARCH;
            query = "-nclientv3";
        }
    }

    @NonNull
    private String encodeQueryParameter(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return URLEncoder.encode(value, Charset.defaultCharset().name()).replace("%20", "+");
        } catch (UnsupportedEncodingException ignore) {
            return value.replace(' ', '+');
        }
    }

    @NonNull
    private String createRawSearchQuery() {
        StringBuilder builder = new StringBuilder();
        if (query != null && !query.trim().isEmpty()) builder.append(query.trim());
        if (tags != null) {
            for (Tag tt : tags) {
                String tagQuery = tt.toQueryTag();
                if (tagQuery == null || tagQuery.isEmpty()) continue;
                if (builder.indexOf(tt.toQueryTag(TagStatus.ACCEPTED)) >= 0) continue;
                if (builder.length() > 0) builder.append(' ');
                builder.append(tagQuery);
            }
        }
        if (ranges != null && !ranges.isDefault()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(ranges.toQuery());
        }
        return builder.toString().trim();
    }

    private void appendSortParameter(StringBuilder builder) {
        if (sortType != null && sortType.getUrlAddition() != null) {
            builder.append("&sort=").append(sortType.getUrlAddition());
        }
    }

    @NonNull
    private String buildDetailUrl(int galleryId) {
        return Utility.getBaseUrl() + "api/v2/galleries/" + galleryId + "?include=related";
    }

    @NonNull
    private String buildFavoritesUrl(int page, @Nullable String rawQuery) {
        StringBuilder builder = new StringBuilder(Utility.getBaseUrl()).append("api/v2/favorites?");
        String encodedQuery = encodeQueryParameter(rawQuery);
        if (!encodedQuery.isEmpty()) builder.append("query=").append(encodedQuery).append('&');
        builder.append("page=").append(page);
        appendSortParameter(builder);
        return builder.toString();
    }

    private void createUrl() {
        String encodedSearchQuery = encodeQueryParameter(createRawSearchQuery());
        StringBuilder requestBuilder = new StringBuilder(Utility.getBaseUrl());
        StringBuilder browserBuilder = new StringBuilder(Utility.getBaseUrl());

        if (requestType == ApiRequestType.BYALL) {
            requestBuilder.append("api/v2/galleries?page=").append(page);
            browserBuilder.append("?page=").append(page);
        } else if (requestType == ApiRequestType.RANDOM) {
            requestBuilder.append("api/v2/galleries/random");
            browserBuilder.append("random/");
        } else if (requestType == ApiRequestType.RANDOM_FAVORITE) {
            requestBuilder = new StringBuilder(buildFavoritesUrl(1, null));
            browserBuilder.append("favorites/random");
        } else if (requestType == ApiRequestType.BYSINGLE) {
            requestBuilder.append("api/v2/galleries/").append(id).append("?include=related");
            browserBuilder.append("g/").append(id).append('/');
        } else if (requestType == ApiRequestType.FAVORITE) {
            requestBuilder.append("api/v2/favorites?");
            if (!encodedSearchQuery.isEmpty()) requestBuilder.append("query=").append(encodedSearchQuery).append('&');
            requestBuilder.append("page=").append(page);
            appendSortParameter(requestBuilder);

            browserBuilder.append("favorites/");
            if (!encodedSearchQuery.isEmpty()) browserBuilder.append("?q=").append(encodedSearchQuery).append('&');
            else browserBuilder.append('?');
            browserBuilder.append("page=").append(page);
            appendSortParameter(browserBuilder);
        } else if (requestType == ApiRequestType.BYSEARCH || requestType == ApiRequestType.BYTAG) {
            requestBuilder.append("api/v2/search?query=").append(encodedSearchQuery).append("&page=").append(page);
            appendSortParameter(requestBuilder);

            browserBuilder.append("search/?q=").append(encodedSearchQuery).append("&page=").append(page);
            appendSortParameter(browserBuilder);
        }
        url = requestBuilder.toString().replace(' ', '+');
        browserUrl = browserBuilder.toString().replace(' ', '+');
        LogUtility.d("Request URL: " + url);
        LogUtility.d("Browser URL: " + getBookmarkURL());
    }

    private String getBookmarkURL() {
        if (browserUrl == null || page < 2 || browserUrl.lastIndexOf('=') < 0) return browserUrl;
        return browserUrl.substring(0, browserUrl.lastIndexOf('=') + 1);
    }

    private static final class ResponsePayload {
        private final int code;
        @Nullable
        private final String contentType;
        @NonNull
        private final String body;

        private ResponsePayload(int code, @Nullable String contentType, @NonNull String body) {
            this.code = code;
            this.contentType = contentType;
            this.body = body;
        }
    }

    public boolean createDocument() throws IOException, InvalidResponseException {
        ResponsePayload payload;
        if (requestType == ApiRequestType.RANDOM) {
            payload = fetchRandomDetailPayload();
        } else if (requestType == ApiRequestType.RANDOM_FAVORITE) {
            payload = fetchRandomFavoriteDetailPayload();
        } else {
            payload = performRequest(url);
        }
        rawResponse = payload.body;
        responseContentType = payload.contentType;
        if (!looksLikeJson(payload)) {
            throw new InvalidResponseException("Unexpected response type: " + payload.contentType);
        }
        return payload.code == HttpURLConnection.HTTP_OK;
    }

    public void parseDocument() throws InvalidResponseException {
        JSONObject responseObject = parseJsonObject(rawResponse);
        if (requestType.isSingle()) doSingleV2(responseObject);
        else doSearchV2(responseObject);
        rawResponse = null;
        responseContentType = null;
    }

    @Override
    public synchronized void start() {
        if (getState() != State.NEW) return;
        if (response.shouldStart(this))
            super.start();
    }

    @Override
    public void run() {
        LogUtility.d("Starting download: " + url);
        if (response != null) response.onStart();
        try {
            boolean bypassRetried = false;
            while (true) {
                try {
                    createDocument();
                    parseDocument();
                    break;
                } catch (InvalidResponseException invalidResponseException) {
                    if (bypassRetried || !BypassNetworkController.getInstance().prepareInvalidContentRetry(
                        url,
                        responseContentType,
                        rawResponse
                    )) {
                        throw invalidResponseException;
                    }
                    bypassRetried = true;
                    rawResponse = null;
                    responseContentType = null;
                    galleries = null;
                }
            }
            if (response != null) {
                response.onSuccess(galleries);
            }
        } catch (Exception e) {
            if (response != null) response.onFailure(e);
        }
        if (response != null) response.onEnd();
        LogUtility.d("Finished download: " + url);
    }

    @NonNull
    private ResponsePayload performRequest(@NonNull String requestUrl) throws IOException {
        try (Response response = Global.getClient(context.get()).newCall(new Request.Builder().url(requestUrl).build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new ResponsePayload(response.code(), response.header("Content-Type"), body);
        }
    }

    private boolean looksLikeJson(@NonNull ResponsePayload payload) {
        if (payload.contentType != null && payload.contentType.toLowerCase().contains("json")) return true;
        String trimmed = payload.body.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    @NonNull
    private JSONObject parseJsonObject(@Nullable String payload) throws InvalidResponseException {
        if (payload == null || payload.trim().isEmpty()) throw new InvalidResponseException("Empty response body");
        try {
            return new JSONObject(payload);
        } catch (JSONException e) {
            throw new InvalidResponseException("Invalid JSON", e);
        }
    }

    @NonNull
    private JSONObject performJsonRequest(@NonNull String requestUrl) throws IOException, InvalidResponseException {
        ResponsePayload payload = performRequest(requestUrl);
        if (!looksLikeJson(payload)) {
            throw new InvalidResponseException("Unexpected response type: " + payload.contentType);
        }
        return parseJsonObject(payload.body);
    }

    @NonNull
    private ResponsePayload fetchRandomDetailPayload() throws IOException, InvalidResponseException {
        JSONObject json = performJsonRequest(Utility.getBaseUrl() + "api/v2/galleries/random");
        int galleryId = optInt(json, "id");
        if (galleryId <= 0) throw new InvalidResponseException("Random gallery endpoint returned no id");
        return performRequest(buildDetailUrl(galleryId));
    }

    @NonNull
    private ResponsePayload fetchRandomFavoriteDetailPayload() throws IOException, InvalidResponseException {
        JSONObject favoritesPage = performJsonRequest(buildFavoritesUrl(1, null));
        int totalPages = findTotalV2(favoritesPage);
        int selectedPage = totalPages <= 1 ? 1 : Utility.RANDOM.nextInt(totalPages) + 1;
        JSONObject sourcePage = selectedPage == 1 ? favoritesPage : performJsonRequest(buildFavoritesUrl(selectedPage, null));
        JSONArray galleriesArray = findGalleryArray(sourcePage, "result", "galleries", "items");
        if (galleriesArray.length() == 0) throw new InvalidResponseException("Favorite random request returned no galleries");
        int selectedIndex = Utility.RANDOM.nextInt(galleriesArray.length());
        JSONObject chosenGallery = galleriesArray.optJSONObject(selectedIndex);
        int galleryId = chosenGallery == null ? 0 : optInt(chosenGallery, "id");
        if (galleryId <= 0) throw new InvalidResponseException("Favorite random gallery item returned no id");
        return performRequest(buildDetailUrl(galleryId));
    }

    private void filterDocumentTags() {
        if (galleries == null || tags == null) return;
        ArrayList<SimpleGallery> galleryTag = new ArrayList<>(galleries.size());
        for (GenericGallery gal : galleries) {
            assert gal instanceof SimpleGallery;
            SimpleGallery gallery = (SimpleGallery) gal;
            if (gallery.hasTags(tags)) {
                galleryTag.add(gallery);
            }
        }
        galleries.clear();
        galleries.addAll(galleryTag);
    }

    private void doSingleV2(JSONObject responseObject) throws InvalidResponseException {
        JSONObject rootData = unwrapDataObject(responseObject);
        JSONObject v2Data = rootData.optJSONObject("gallery");
        if (v2Data == null) v2Data = rootData;
        try {
            JSONArray relatedArray = findGalleryArray(rootData, "related", "related_galleries", "relatedGalleries");
            if (relatedArray.length() == 0 && v2Data != rootData) {
                relatedArray = findGalleryArray(v2Data, "related", "related_galleries", "relatedGalleries");
            }
            ArrayList<SimpleGallery> relatedGalleries = new ArrayList<>(relatedArray.length());
            for (int i = 0; i < relatedArray.length(); i++) {
                JSONObject related = relatedArray.optJSONObject(i);
                if (related != null) relatedGalleries.add(SimpleGallery.fromV2ListItem(context.get(), related));
            }

            boolean isFavorite = requestType == ApiRequestType.RANDOM_FAVORITE ||
                optBoolean(rootData, "is_favorite", "favorite", "favorited") ||
                optBoolean(v2Data, "is_favorite", "favorite", "favorited");

            galleries = new ArrayList<>(1);
            galleries.add(new Gallery(context.get(), convertV2DetailToLegacy(v2Data).toString(), relatedGalleries, isFavorite));
        } catch (IOException | JSONException e) {
            throw new InvalidResponseException("Unable to parse gallery detail", e);
        }
    }

    @NonNull
    private JSONObject convertV2DetailToLegacy(@NonNull JSONObject v2Data) throws JSONException {
        JSONObject legacy = new JSONObject();

        int idValue = optInt(v2Data, "id");
        if (idValue > 0) legacy.put("id", idValue);

        Object mediaValue = v2Data.opt("media_id");
        if (mediaValue != null && mediaValue != JSONObject.NULL) {
            legacy.put("media_id", String.valueOf(mediaValue));
        }

        long uploadDateValue = optLong(v2Data, "upload_date", "created_at");
        if (uploadDateValue > 0) legacy.put("upload_date", uploadDateValue);

        int favoriteCountValue = optInt(v2Data, "num_favorites", "favorite_count", "favorites", "total_favorites");
        if (favoriteCountValue >= 0) legacy.put("num_favorites", favoriteCountValue);

        int pageCountValue = optInt(v2Data, "num_pages", "pages_count", "page_count", "total_pages");
        if (pageCountValue >= 0) legacy.put("num_pages", pageCountValue);

        legacy.put("title", buildLegacyTitle(v2Data));
        legacy.put("tags", buildLegacyTags(v2Data));
        legacy.put("images", buildLegacyImages(v2Data));

        Object errorValue = v2Data.opt("error");
        if (errorValue != null && errorValue != JSONObject.NULL) legacy.put("error", errorValue);
        return legacy;
    }

    @NonNull
    private JSONObject buildLegacyTitle(@NonNull JSONObject source) throws JSONException {
        JSONObject title = new JSONObject();
        JSONObject currentTitle = source.optJSONObject("title");

        String english = firstNonEmpty(
            currentTitle == null ? null : currentTitle.optString("english"),
            source.optString("english_title"),
            source.optString("title_english")
        );
        String japanese = firstNonEmpty(
            currentTitle == null ? null : currentTitle.optString("japanese"),
            source.optString("japanese_title"),
            source.optString("title_japanese")
        );
        String pretty = firstNonEmpty(
            currentTitle == null ? null : currentTitle.optString("pretty"),
            source.optString("pretty_title"),
            source.optString("title"),
            english,
            japanese
        );

        title.put("english", english);
        title.put("japanese", japanese);
        title.put("pretty", pretty);
        return title;
    }

    @NonNull
    private JSONArray buildLegacyTags(@NonNull JSONObject source) throws JSONException {
        JSONArray legacyTags = new JSONArray();
        JSONArray tagsArray = source.optJSONArray("tags");
        if (tagsArray != null) {
            for (int i = 0; i < tagsArray.length(); i++) {
                Object value = tagsArray.opt(i);
                if (value instanceof JSONObject) {
                    legacyTags.put(normalizeLegacyTag((JSONObject) value));
                } else {
                    int tagId = optInt(tagsArray, i);
                    if (tagId > 0) legacyTags.put(tagToJson(tagId, null));
                }
            }
        }

        if (legacyTags.length() > 0) return legacyTags;

        JSONArray tagIds = source.optJSONArray("tag_ids");
        if (tagIds == null) return legacyTags;
        for (int i = 0; i < tagIds.length(); i++) {
            int tagId = optInt(tagIds, i);
            if (tagId > 0) legacyTags.put(tagToJson(tagId, null));
        }
        return legacyTags;
    }

    @NonNull
    private JSONObject normalizeLegacyTag(@NonNull JSONObject sourceTag) throws JSONException {
        int tagId = optInt(sourceTag, "id");
        Tag dbTag = tagId > 0 ? Queries.TagTable.getTagById(tagId) : null;
        return tagToJson(tagId, dbTag != null ? dbTag : new Tag(
            firstNonEmpty(sourceTag.optString("name"), "unknown"),
            optInt(sourceTag, "count"),
            tagId,
            TagType.typeByName(firstNonEmpty(sourceTag.optString("type"), TagType.TAG.getSingle())),
            TagStatus.DEFAULT
        ));
    }

    @NonNull
    private JSONObject tagToJson(int tagId, @Nullable Tag fallbackTag) throws JSONException {
        Tag resolved = Queries.TagTable.getTagById(tagId);
        if (resolved == null) resolved = fallbackTag;

        JSONObject normalized = new JSONObject();
        if (resolved != null) {
            normalized.put("count", resolved.getCount());
            normalized.put("type", resolved.getTypeSingleName());
            normalized.put("id", resolved.getId());
            normalized.put("name", resolved.getName());
            return normalized;
        }

        normalized.put("count", 0);
        normalized.put("type", TagType.TAG.getSingle());
        normalized.put("id", tagId);
        normalized.put("name", "unknown");
        return normalized;
    }

    @NonNull
    private JSONObject buildLegacyImages(@NonNull JSONObject source) throws JSONException {
        JSONObject images = new JSONObject();
        JSONObject sourceImages = source.optJSONObject("images");
        images.put("cover", normalizeLegacyImage(sourceImages == null ? source.opt("cover") : sourceImages.opt("cover")));
        images.put("thumbnail", normalizeLegacyImage(sourceImages == null ? source.opt("thumbnail") : sourceImages.opt("thumbnail")));

        JSONArray pages = new JSONArray();
        JSONArray sourcePages = sourceImages == null ? null : sourceImages.optJSONArray("pages");
        if (sourcePages == null) sourcePages = source.optJSONArray("pages");
        if (sourcePages != null) {
            for (int i = 0; i < sourcePages.length(); i++) {
                pages.put(normalizeLegacyImage(sourcePages.opt(i)));
            }
        }
        images.put("pages", pages);
        return images;
    }

    @NonNull
    private JSONObject normalizeLegacyImage(@Nullable Object sourceImage) throws JSONException {
        JSONObject normalized = new JSONObject();
        if (sourceImage instanceof JSONObject) {
            JSONObject imageObject = (JSONObject) sourceImage;
            Object imageType = imageObject.opt("t");
            if (imageType != null && imageType != JSONObject.NULL) normalized.put("t", imageType);
            else normalized.put("t", deriveLegacyImageType(imageObject.optString("path")));

            int width = optInt(imageObject, "w", "width");
            if (width > 0) normalized.put("w", width);

            int height = optInt(imageObject, "h", "height");
            if (height > 0) normalized.put("h", height);
        } else if (sourceImage instanceof String) {
            normalized.put("t", deriveLegacyImageType((String) sourceImage));
        } else {
            normalized.put("t", "j");
        }
        if (!normalized.has("t")) normalized.put("t", "j");
        return normalized;
    }

    @NonNull
    private String deriveLegacyImageType(@Nullable String path) {
        if (path == null || path.isEmpty()) return "j";
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int extensionIndex = fileName.indexOf('.');
        if (extensionIndex >= 0) fileName = fileName.substring(extensionIndex + 1);
        com.maxwai.nclientv3.api.enums.ImageExt ext = Objects.requireNonNullElse(
            Page.stringToExt(fileName),
            com.maxwai.nclientv3.api.enums.ImageExt.JPG
        );
        switch (ext) {
            case PNG:
                return "p";
            case GIF:
                return "g";
            case WEBP:
            case GIF_WEBP:
            case JPG_WEBP:
            case PNG_WEBP:
            case WEBP_WEBP:
                return "w";
            case JPG:
            default:
                return "j";
        }
    }

    private void doSearchV2(JSONObject responseObject) throws InvalidResponseException {
        JSONObject source = unwrapDataObject(responseObject);
        JSONArray galleryArray = findGalleryArray(source, "result", "galleries", "items");
        galleries = new ArrayList<>(galleryArray.length());
        try {
            for (int i = 0; i < galleryArray.length(); i++) {
                JSONObject galleryJson = galleryArray.optJSONObject(i);
                if (galleryJson != null) galleries.add(SimpleGallery.fromV2ListItem(context.get(), galleryJson));
            }
        } catch (Exception e) {
            throw new InvalidResponseException("Unable to parse gallery list", e);
        }
        pageCount = findTotalV2(source);
        if (Global.isExactTagMatch()) filterDocumentTags();
    }

    private int findTotalV2(@NonNull JSONObject responseObject) {
        JSONObject source = unwrapDataObject(responseObject);
        int totalPages = optInt(source, "num_pages", "pages", "page_count", "total_pages", "last_page");
        JSONObject pagination = source.optJSONObject("pagination");
        JSONObject meta = source.optJSONObject("meta");
        JSONObject result = source.optJSONObject("result");
        if (totalPages <= 0 && pagination != null) {
            totalPages = optInt(pagination, "pages", "page_count", "total_pages", "last_page");
        }
        if (totalPages <= 0 && meta != null) {
            totalPages = optInt(meta, "pages", "page_count", "total_pages", "last_page");
        }
        if (totalPages <= 0 && result != null) {
            totalPages = optInt(result, "pages", "page_count", "total_pages", "last_page");
        }
        if (totalPages <= 0) {
            int totalItems = optInt(source, "total", "total_items", "count");
            int perPage = optInt(source, "per_page", "page_size");
            if (perPage <= 0 && pagination != null) perPage = optInt(pagination, "per_page", "page_size");
            if (perPage <= 0 && meta != null) perPage = optInt(meta, "per_page", "page_size");
            if (perPage > 0 && totalItems > 0) {
                totalPages = (totalItems + perPage - 1) / perPage;
            }
        }
        return totalPages > 0 ? totalPages : Math.max(1, page);
    }

    @NonNull
    private JSONObject unwrapDataObject(@NonNull JSONObject responseObject) {
        JSONObject data = responseObject.optJSONObject("data");
        return data == null ? responseObject : data;
    }

    @NonNull
    private JSONArray findGalleryArray(@NonNull JSONObject responseObject, String... keys) {
        JSONObject source = unwrapDataObject(responseObject);
        for (String key : keys) {
            JSONArray value = extractGalleryArray(source.opt(key));
            if (value != null) return value;
        }
        for (String key : keys) {
            JSONArray value = extractGalleryArray(responseObject.opt(key));
            if (value != null) return value;
        }
        return new JSONArray();
    }

    @Nullable
    private JSONArray extractGalleryArray(@Nullable Object value) {
        if (value instanceof JSONArray) return (JSONArray) value;
        if (!(value instanceof JSONObject)) return null;

        JSONObject object = (JSONObject) value;
        JSONArray nested = object.optJSONArray("items");
        if (nested != null) return nested;
        nested = object.optJSONArray("result");
        if (nested != null) return nested;
        nested = object.optJSONArray("galleries");
        if (nested != null) return nested;
        nested = object.optJSONArray("data");
        if (nested != null) return nested;
        nested = object.optJSONArray("related");
        return nested;
    }

    private int optInt(@NonNull JSONObject source, String... keys) {
        for (String key : keys) {
            Object value = source.opt(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) {
                try {
                    return Integer.parseInt((String) value);
                } catch (NumberFormatException ignore) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private long optLong(@NonNull JSONObject source, String... keys) {
        for (String key : keys) {
            Object value = source.opt(key);
            if (value instanceof Number) return ((Number) value).longValue();
            if (value instanceof String) {
                try {
                    return Long.parseLong((String) value);
                } catch (NumberFormatException ignore) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private int optInt(@NonNull JSONArray source, int index) {
        Object value = source.opt(index);
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

    private boolean optBoolean(@NonNull JSONObject source, String... keys) {
        for (String key : keys) {
            Object value = source.opt(key);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof String) return Boolean.parseBoolean((String) value);
        }
        return false;
    }

    @NonNull
    private String firstNonEmpty(@Nullable String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    public void setSortType(SortType sortType) {
        this.sortType = sortType;
        createUrl();
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
        createUrl();
    }

    public List<GenericGallery> getGalleries() {
        return galleries;
    }

    public String getUrl() {
        return url;
    }

    public String getBrowserUrl() {
        return browserUrl == null ? url : browserUrl;
    }

    public ApiRequestType getRequestType() {
        return requestType;
    }

    public int getPageCount() {
        return pageCount;
    }

    public Tag getTag() {
        Tag t = null;
        if (tags == null) return null;
        for (Tag tt : tags) {
            if (tt.getType() != TagType.LANGUAGE)
                return tt;
            t = tt;
        }
        return t;
    }

    public static class InvalidResponseException extends Exception {
        public InvalidResponseException() {
            super();
        }

        public InvalidResponseException(String message) {
            super(message);
        }

        public InvalidResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public interface InspectorResponse {
        boolean shouldStart(InspectorV3 inspector);

        void onSuccess(List<GenericGallery> galleries);

        void onFailure(Exception e);

        void onStart();

        void onEnd();
    }

    public static abstract class DefaultInspectorResponse implements InspectorResponse {
        @Override
        public boolean shouldStart(InspectorV3 inspector) {
            return true;
        }

        @Override
        public void onStart() {
        }

        @Override
        public void onEnd() {
        }

        @Override
        public void onSuccess(List<GenericGallery> galleries) {
        }

        @Override
        public void onFailure(Exception e) {
            LogUtility.e(e.getLocalizedMessage(), e);
        }
    }
}
