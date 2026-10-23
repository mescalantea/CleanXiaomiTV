package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigTest {

    private static final String PRIVATE = "{\"profiles\":["
            + "{\"name\":\"Alex\",\"jellyfin_user_id\":\"u1\",\"youtube_channel_id\":\"UCalex\",\"youtube_channels\":[\"UCa\",\"UCshared\"]},"
            + "{\"name\":\"Sam\"},"
            + "{\"jellyfin_user_id\":\"no name\"}, 3]}";

    @Test
    public void readsNamedProfiles() throws Exception {
        List<Config.Profile> p = Config.profiles(new JSONObject(PRIVATE));
        assertEquals(2, p.size());
        assertEquals("Alex", p.get(0).name);
        assertEquals("u1", p.get(0).jellyfinUserId);
        assertEquals("UCalex", p.get(0).youtubeChannelId);
        assertEquals(Arrays.asList("UCa", "UCshared"), p.get(0).youtubeChannels);
        assertEquals("", p.get(1).jellyfinUserId);
        assertTrue(Config.profiles(new JSONObject("{}")).isEmpty());
    }

    @Test
    public void profileValuesMergeWithTheFlatOnes() throws Exception {
        List<Config.Profile> p = Config.profiles(new JSONObject(PRIVATE));
        List<String> flat = Arrays.asList("UCshared", "UCall");
        Config.Profile base = new Config.Profile("", "flat-user", "UCflat", flat);
        Config.Profile alex = Config.merge(p, "Alex", base);
        assertEquals("u1", alex.jellyfinUserId);
        assertEquals("UCalex", alex.youtubeChannelId);
        assertEquals(Arrays.asList("UCshared", "UCall", "UCa"), alex.youtubeChannels);
        Config.Profile sam = Config.merge(p, "Sam", base);
        assertEquals("flat-user", sam.jellyfinUserId);
        assertEquals("UCflat", sam.youtubeChannelId);
        assertEquals(flat, sam.youtubeChannels);
    }

    @Test
    public void unknownChoiceTakesTheFirstProfileAndNoProfilesTheFlatKeys() {
        Config.Profile base = new Config.Profile("", "flat", "", Arrays.asList("UCx"));
        Config.Profile first = Config.merge(Arrays.asList(new Config.Profile("Alex", "u1", "", Collections.emptyList())),
                "Gone", base);
        assertEquals("Alex", first.name);
        Config.Profile flat = Config.merge(Collections.emptyList(), "", base);
        assertEquals("", flat.name);
        assertEquals("flat", flat.jellyfinUserId);
        assertEquals(Arrays.asList("UCx"), flat.youtubeChannels);
    }
}
