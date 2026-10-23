package com.gathin.tvhome;

/** One poster on the Home shelf: a Jellyfin item, a YouTube video, or a placeholder while loading. */
final class ShelfItem {
    enum Source { JELLYFIN, YOUTUBE, LIVE, PLACEHOLDER }

    final Source source;
    /** Jellyfin item id or YouTube video id. */
    final String id;
    final String title;
    /** Second line, or empty. */
    final String subtitle;
    /** Poster URL, or null when there is none. */
    final String image;
    /** Percent watched, or -1 when not started. */
    final int progress;
    /** Publication time in ms (YouTube), 0 when unknown. */
    final long published;
    /** The YouTube channel id, or empty. */
    final String channel;

    ShelfItem(Source source, String id, String title, String subtitle, String image, int progress, long published) {
        this(source, id, title, subtitle, image, progress, published, "");
    }

    ShelfItem(Source source, String id, String title, String subtitle, String image, int progress, long published,
              String channel) {
        this.source = source;
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.image = image;
        this.progress = progress;
        this.published = published;
        this.channel = channel;
    }
}
