package com.maxwai.nclientv3.async.downloader;

public class PageChecker extends Thread {
    @Override
    public void run() {
        for (GalleryDownloaderV2 g : DownloadQueue.getDownloaders()) {
            if (isInterrupted()) return;
            try {
                if (g.hasData()) g.initDownload();
            } catch (Exception e) {
                // Continue checking other galleries on error
            }
        }
    }
}
