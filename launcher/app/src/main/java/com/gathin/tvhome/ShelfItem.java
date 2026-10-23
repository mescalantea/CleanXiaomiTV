package com.gathin.tvhome;

/** One card on Home: a Jellyfin item, a YouTube video, a live channel, a sports event, or a placeholder. */
final class ShelfItem {
    enum Source { JELLYFIN, YOUTUBE, LIVE, SPORT, PLACEHOLDER }

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
    /** The YouTube channel id, the live channel's tvg-id, or the Jellyfin series id; or empty. */
    final String channel;
    /** A few lines about it for the hero, or empty. */
    final String description;
    /** A large picture for the hero's background, or null. */
    final String backdrop;
    /** A new episode not yet watched ("NUEVO" badge). */
    final boolean fresh;

    ShelfItem(Source source, String id, String title, String subtitle, String image, int progress, long published) {
        this(source, id, title, subtitle, image, progress, published, "");
    }

    ShelfItem(Source source, String id, String title, String subtitle, String image, int progress, long published,
              String channel) {
        this(source, id, title, subtitle, image, progress, published, channel, "", null, false);
    }

    ShelfItem(Source source, String id, String title, String subtitle, String image, int progress, long published,
              String channel, String description, String backdrop, boolean fresh) {
        this.source = source;
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.image = image;
        this.progress = progress;
        this.published = published;
        this.channel = channel;
        this.description = description;
        this.backdrop = backdrop;
        this.fresh = fresh;
    }

    /** The same item marked as a new episode. */
    ShelfItem asFresh() {
        return new ShelfItem(source, id, title, subtitle, image, progress, published, channel, description, backdrop, true);
    }
}
