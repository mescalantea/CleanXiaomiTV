package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shelf's rows in a fixed order (resume, next up, live TV, sports, YouTube). A section that loads with nothing to show
 * yet ends in placeholder cards until it is done, so results can arrive one source at a time, and YouTube
 * feed by feed, without anything on screen moving.
 */
final class ShelfSections {
    static final int RESUME = 0, NEXT_UP = 1, LIVE = 2, SPORTS = 3, YOUTUBE = 4;
    private static final int[] PLACEHOLDERS = {2, 2, 2, 2, 3};

    @SuppressWarnings("unchecked")
    private final List<ShelfItem>[] items = new List[] {Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList()};
    private final boolean[] loading = new boolean[items.length];

    /** A fetch for this section starts; it shows placeholders only while it has no items of its own. */
    void start(int section) {
        loading[section] = items[section].isEmpty();
    }

    /** The section's new items, or null when the fetch failed: the items it had stay. */
    void set(int section, List<ShelfItem> fresh) {
        loading[section] = false;
        if (fresh != null) items[section] = fresh;
    }

    /** Adds videos at the end of the section, which stays loading; see {@link YouTubeFeed#append}. */
    void append(int section, List<ShelfItem> batch, int max) {
        items[section] = YouTubeFeed.append(items[section], batch, max);
    }

    /** The section is done loading and keeps what it has. */
    void finish(int section) {
        loading[section] = false;
    }

    void clear() {
        for (int i = 0; i < items.length; i++) {
            items[i] = Collections.emptyList();
            loading[i] = false;
        }
    }

    /** All rows in order; next-up episodes already in the resume list are left out. */
    List<ShelfItem> rows() {
        List<ShelfItem> out = new ArrayList<>();
        Set<String> resumed = new HashSet<>();
        for (ShelfItem i : items[RESUME]) resumed.add(i.id);
        for (int s = 0; s < items.length; s++) {
            for (ShelfItem i : items[s]) if (s != NEXT_UP || !resumed.contains(i.id)) out.add(i);
            if (!loading[s]) continue;
            for (int n = 0; n < PLACEHOLDERS[s]; n++) {
                out.add(new ShelfItem(ShelfItem.Source.PLACEHOLDER, s + "-" + n, "", "", null, -1, 0));
            }
        }
        return out;
    }

    /** Identity across updates: same source and id. */
    static String key(ShelfItem item) {
        return item.source + ":" + item.id;
    }
}
