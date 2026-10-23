package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShelfSectionsTest {

    private static ShelfItem jf(String id) {
        return new ShelfItem(ShelfItem.Source.JELLYFIN, id, id, "", null, -1, 0);
    }

    private static ShelfItem yt(String id) {
        return new ShelfItem(ShelfItem.Source.YOUTUBE, id, id, "", null, -1, 0);
    }

    private static String ids(List<ShelfItem> rows) {
        StringBuilder b = new StringBuilder();
        for (ShelfItem i : rows) b.append(i.source == ShelfItem.Source.PLACEHOLDER ? "_" : i.id).append(' ');
        return b.toString().trim();
    }

    @Test
    public void loadingSectionsShowPlaceholdersInOrder() {
        ShelfSections s = new ShelfSections();
        s.start(ShelfSections.RESUME);
        s.start(ShelfSections.NEXT_UP);
        s.start(ShelfSections.YOUTUBE);
        assertEquals("_ _ _ _ _ _ _ _", ids(s.rows()));
        s.set(ShelfSections.NEXT_UP, Arrays.asList(jf("n1")));
        assertEquals("_ _ n1 _ _ _ _", ids(s.rows()));
        s.set(ShelfSections.RESUME, Arrays.asList(jf("r1"), jf("n1")));
        assertEquals("r1 n1 _ _ _ _", ids(s.rows()));
        s.set(ShelfSections.YOUTUBE, Arrays.asList(yt("v1")));
        assertEquals("r1 n1 v1", ids(s.rows()));
    }

    @Test
    public void aFailedOrEmptySourceRemovesItsPlaceholders() {
        ShelfSections s = new ShelfSections();
        s.start(ShelfSections.RESUME);
        s.start(ShelfSections.YOUTUBE);
        s.set(ShelfSections.RESUME, null);
        s.set(ShelfSections.YOUTUBE, Collections.emptyList());
        assertTrue(s.rows().isEmpty());
    }

    @Test
    public void aRefreshKeepsItemsInsteadOfPlaceholdersAndAFailureKeepsThem() {
        ShelfSections s = new ShelfSections();
        s.set(ShelfSections.YOUTUBE, Arrays.asList(yt("v1")));
        s.start(ShelfSections.YOUTUBE);
        assertEquals("v1", ids(s.rows()));
        s.set(ShelfSections.YOUTUBE, null);
        assertEquals("v1", ids(s.rows()));
        s.clear();
        assertTrue(s.rows().isEmpty());
    }

    @Test
    public void keysTellSourcesApart() {
        assertTrue(!ShelfSections.key(jf("a")).equals(ShelfSections.key(yt("a"))));
    }
}
