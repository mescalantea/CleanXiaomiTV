package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shelf's rows in a fixed order (resume, next up, YouTube), each section showing placeholder cards
 * while it loads with nothing to show yet, so results can arrive one source at a time without the others
 * moving.
 */
final class ShelfSections {
    static final int RESUME = 0, NEXT_UP = 1, YOUTUBE = 2;
    private static final int[] PLACEHOLDERS = {2, 2, 4};

    @SuppressWarnings("unchecked")
    private final List<ShelfItem>[] items = new List[] {Collections.emptyList(), Collections.emptyList(), Collections.emptyList()};
    private final boolean[] loading = new boolean[3];

    /** A fetch for this section starts; it shows placeholders only while it has no items of its own. */
    void start(int section) {
        loading[section] = items[section].isEmpty();
    }

    /** The section's new items, or null when the fetch failed: the items it had stay. */
    void set(int section, List<ShelfItem> fresh) {
        loading[section] = false;
        if (fresh != null) items[section] = fresh;
    }

    void clear() {
        for (int i = 0; i < 3; i++) {
            items[i] = Collections.emptyList();
            loading[i] = false;
        }
    }

    /** All rows in order; next-up episodes already in the resume list are left out. */
    List<ShelfItem> rows() {
        List<ShelfItem> out = new ArrayList<>();
        Set<String> resumed = new HashSet<>();
        for (ShelfItem i : items[RESUME]) resumed.add(i.id);
        for (int s = 0; s < 3; s++) {
            if (loading[s]) {
                for (int n = 0; n < PLACEHOLDERS[s]; n++) {
                    out.add(new ShelfItem(ShelfItem.Source.PLACEHOLDER, s + "-" + n, "", "", null, -1, 0));
                }
                continue;
            }
            for (ShelfItem i : items[s]) if (s != NEXT_UP || !resumed.contains(i.id)) out.add(i);
        }
        return out;
    }

    /** Identity across updates: same source and id. */
    static String key(ShelfItem item) {
        return item.source + ":" + item.id;
    }
}
