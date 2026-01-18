package com.maxwai.nclientv3.async;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ScrapeTags extends Worker {
    private static final int DAYS_UNTIL_SCRAPE = 7;
    private static final String UNIQUE_WORK_NAME = "ScrapeTags";
    private static final String DATA_FOLDER = "https://raw.githubusercontent.com/maxwai/NClientV3/main/data/";
    private static final String TAGS = DATA_FOLDER + "tags.json";
    private static final String VERSION = DATA_FOLDER + "tagsVersion";

    public ScrapeTags(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void startWork(Context context) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();
        OneTimeWorkRequest scrapeTagsWorkRequest = new OneTimeWorkRequest.Builder(ScrapeTags.class)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, scrapeTagsWorkRequest);
    }

    private int getNewVersionCode() throws IOException {
        try (Response x = Global.getClient(getApplicationContext()).newCall(new Request.Builder().url(VERSION).build()).execute()) {
            ResponseBody body = x.body();
            try {
                int k = Integer.parseInt(body.string().trim());
                LogUtility.d("Found version: " + k);
                return k;
            } catch (NumberFormatException e) {
                LogUtility.e("Unable to convert", e);
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences preferences = getApplicationContext().getSharedPreferences("Settings", 0);
        Date nowTime = new Date();
        Date lastTime = new Date(preferences.getLong("lastSync", nowTime.getTime()));
        int lastVersion = preferences.getInt("lastTagsVersion", -1), newVersion;
        if (!enoughDayPassed(nowTime, lastTime)) return Result.success();

        LogUtility.d("Scraping tags");
        try {
            newVersion = getNewVersionCode();
            if (lastVersion > -1 && lastVersion >= newVersion) {
                preferences.edit()
                    .putLong("lastSync", nowTime.getTime())
                    .putInt("lastTagsVersion", newVersion)
                    .apply();
                return Result.success();
            }
            List<Tag> tags = Queries.TagTable.getAllFiltered();
            fetchTags();
            for (Tag t : tags) Queries.TagTable.updateStatus(t.getId(), t.getStatus());
        } catch (IOException e) {
            LogUtility.w("Error updating Tags", e);
            return Result.retry();
        } catch (Exception e) {
            LogUtility.w("Error updating Tags", e);
            return Result.failure();
        }
        LogUtility.d("End scraping");
        preferences.edit()
            .putLong("lastSync", nowTime.getTime())
            .putInt("lastTagsVersion", newVersion)
            .apply();
        return Result.success();
    }

    private void fetchTags() throws IOException {
        try (Response x = Global.getClient(getApplicationContext())
            .newCall(new Request.Builder().url(TAGS).build())
            .execute()) {
            ResponseBody body = x.body();
            int batchSize = 5000;
            if (body == null) throw new IOException("Empty tag response body");
            try (Reader reader = body.charStream();
                 JsonReader jr = new JsonReader(reader)) {
                List<Tag> tags = new ArrayList<>(batchSize);
                jr.beginArray();
                while (jr.hasNext()) {
                    tags.add(readTag(jr));
                    if (tags.size() >= batchSize) {
                        Queries.TagTable.insertScrape(tags, true);
                        tags.clear();
                    }
                }
                jr.endArray();
                if (!tags.isEmpty()) Queries.TagTable.insertScrape(tags, true);
            }
        }
    }

    private Tag readTag(JsonReader reader) throws IOException {
        reader.beginArray();
        int id = reader.nextInt();
        String name = reader.nextString();
        int count = reader.nextInt();
        TagType type = TagType.values[reader.nextInt()];
        while (reader.hasNext()) reader.skipValue();
        reader.endArray();
        return new Tag(name, count, id, type, TagStatus.DEFAULT);
    }

    private boolean enoughDayPassed(Date nowTime, Date lastTime) {
        //first start or never completed
        if (nowTime.getTime() == lastTime.getTime()) return true;
        int daysBetween = 0;
        Calendar now = Calendar.getInstance(), last = Calendar.getInstance();
        now.setTime(nowTime);
        last.setTime(lastTime);
        while (last.before(now)) {
            last.add(Calendar.DAY_OF_MONTH, 1);
            daysBetween++;
            if (daysBetween > DAYS_UNTIL_SCRAPE)
                return true;
        }
        LogUtility.d("Passed " + daysBetween + " days since last scrape");
        return false;
    }
}
