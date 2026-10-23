package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.majnioui.cleanxiaomitv/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the dock and the look without a rebuild:
 * {@code tools/theme.sh themes/<name>} pushes a theme folder there and restarts Home.
 * {@code private.json} in the same folder is read after it, for the owner's own values that never go in the
 * repo: the Jellyfin server and key, the YouTube channels and the weather location.
 *
 * The few switches in the Settings menu (the analog clock, apps per row) are saved on the TV and win over both.
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String FILE_NAME = "config.json";
    static final String PRIVATE_NAME = "private.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";

    final List<String> dockApps;
    final List<String> hiddenApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String sourcePanelService;
    final String sourcePanelAction;
    final String inputAction;
    final int gridColumns;
    /** An analog clock on the screensaver instead of the digital one. */
    final boolean dreamAnalog;
    final boolean dreamPhotos;
    final boolean nowPlaying;
    final boolean homePhoto;
    final String vpnApp;
    /** Open-Meteo location for the weather line; an empty latitude hides it. */
    final String weatherLatitude;
    final String weatherLongitude;
    final String weatherPlace;
    final boolean showHomeSettingsShortcut;
    /** Jellyfin server (https://...), API key and user id; the shelf shows nothing from Jellyfin unless all three are set. */
    final String jellyfinUrl;
    final String jellyfinApiKey;
    final String jellyfinUserId;
    /** YouTube channel ids whose latest videos go on the shelf. */
    final List<String> youtubeChannels;
    /** tvg-ids of the live channels for the shelf's "En directo" part, in order. */
    final List<String> tvChannels;
    /** YouTube Data API key, to read a person's public subscriptions; empty without one. */
    final String youtubeApiKey;
    /** The person's own YouTube channel id, whose subscriptions feed the shelf (with youtubeApiKey). */
    final String youtubeChannelId;
    /** The people using the TV (private.json, profiles); empty when there is only the flat keys above. */
    final List<Profile> profiles;

    /** One person's own values; empty fields fall back to the flat keys (see {@link #profile}). */
    static final class Profile {
        final String name;
        final String jellyfinUserId;
        final String youtubeChannelId;
        final List<String> youtubeChannels;
        /** Picture for the profile button (https), or empty for the Jellyfin user's avatar. */
        final String photo;
        final List<String> tvChannels;

        Profile(String name, String jellyfinUserId, String youtubeChannelId, List<String> youtubeChannels) {
            this(name, jellyfinUserId, youtubeChannelId, youtubeChannels, "", Collections.emptyList());
        }

        Profile(String name, String jellyfinUserId, String youtubeChannelId, List<String> youtubeChannels, String photo,
                List<String> tvChannels) {
            this.name = name;
            this.photo = photo;
            this.tvChannels = Collections.unmodifiableList(tvChannels);
            this.jellyfinUserId = jellyfinUserId;
            this.youtubeChannelId = youtubeChannelId;
            this.youtubeChannels = Collections.unmodifiableList(youtubeChannels);
        }
    }
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;

    private Config(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                   String sourcePanelService, String sourcePanelAction, String inputAction,
                   int gridColumns,
                   boolean dreamAnalog, boolean dreamPhotos, boolean nowPlaying, boolean homePhoto, String vpnApp,
                   String weatherLatitude, String weatherLongitude, String weatherPlace, boolean showHomeSettingsShortcut,
                   String jellyfinUrl, String jellyfinApiKey, String jellyfinUserId, List<String> youtubeChannels, List<String> tvChannels, String youtubeApiKey, String youtubeChannelId, List<Profile> profiles, int backgroundColor,
                   File wallpaper, boolean overlayApplied) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.gridColumns = gridColumns;
        this.dreamAnalog = dreamAnalog;
        this.dreamPhotos = dreamPhotos;
        this.nowPlaying = nowPlaying;
        this.homePhoto = homePhoto;
        this.vpnApp = vpnApp;
        this.weatherLatitude = weatherLatitude;
        this.weatherLongitude = weatherLongitude;
        this.weatherPlace = weatherPlace;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.jellyfinUrl = jellyfinUrl;
        this.jellyfinApiKey = jellyfinApiKey;
        this.jellyfinUserId = jellyfinUserId;
        this.youtubeChannels = Collections.unmodifiableList(youtubeChannels);
        this.tvChannels = Collections.unmodifiableList(tvChannels);
        this.youtubeApiKey = youtubeApiKey;
        this.youtubeChannelId = youtubeChannelId;
        this.profiles = Collections.unmodifiableList(profiles);
        this.backgroundColor = backgroundColor;
        this.wallpaper = wallpaper;
        this.overlayApplied = overlayApplied;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String inputAction = res.getString(R.string.input_action).trim();
        int gridColumns = res.getInteger(R.integer.grid_columns);
        boolean dreamAnalog = res.getBoolean(R.bool.dream_analog);
        boolean photos = res.getBoolean(R.bool.dream_photos);
        boolean playing = res.getBoolean(R.bool.now_playing);
        boolean homePhoto = res.getBoolean(R.bool.home_photo);
        String vpn = res.getString(R.string.vpn_app).trim();
        String weatherLat = res.getString(R.string.weather_latitude).trim();
        String weatherLon = res.getString(R.string.weather_longitude).trim();
        String weatherPlace = res.getString(R.string.weather_place).trim();
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        int background = 0xFF000000;
        boolean applied = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        JSONObject json = read(file);
        if (json != null) {
            applied = true;
            dock = stringList(json, "dock_apps", dock);
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            panelService = string(json, "source_panel_service", panelService);
            panelAction = string(json, "source_panel_action", panelAction);
            inputAction = string(json, "input_action", inputAction);
            gridColumns = integer(json, "grid_columns", gridColumns, 1, 8);
            dreamAnalog = bool(json, "dream_analog", dreamAnalog);
            photos = bool(json, "dream_photos", photos);
            playing = bool(json, "now_playing", playing);
            homePhoto = bool(json, "home_photo", homePhoto);
            vpn = string(json, "vpn_app", vpn);
            weatherLat = string(json, "weather_latitude", weatherLat);
            weatherLon = string(json, "weather_longitude", weatherLon);
            weatherPlace = string(json, "weather_place", weatherPlace);
            homeSettings = bool(json, "show_home_settings_shortcut", homeSettings);
            background = color(json, "background_color", background);
        }
        String jellyfinUrl = "", jellyfinKey = "", jellyfinUser = "", youtubeKey = "", youtubeId = "";
        List<String> youtube = new ArrayList<>(), tv = new ArrayList<>();
        List<Profile> profiles = new ArrayList<>();
        JSONObject own = read(dir != null ? new File(dir, PRIVATE_NAME) : null);
        if (own != null) {
            jellyfinUrl = string(own, "jellyfin_url", jellyfinUrl);
            jellyfinKey = string(own, "jellyfin_api_key", jellyfinKey);
            jellyfinUser = string(own, "jellyfin_user_id", jellyfinUser);
            youtube = stringList(own, "youtube_channels", youtube);
            tv = stringList(own, "tv_channels", tv);
            youtubeKey = string(own, "youtube_api_key", youtubeKey);
            youtubeId = string(own, "youtube_channel_id", youtubeId);
            profiles = profiles(own);
            weatherLat = string(own, "weather_latitude", weatherLat);
            weatherLon = string(own, "weather_longitude", weatherLon);
            weatherPlace = string(own, "weather_place", weatherPlace);
        }
        // Switched on the TV itself (Settings menu): wins over config.xml and a theme.
        SharedPreferences toggles = toggles(ctx);
        dreamAnalog = toggles.getBoolean(DREAM_ANALOG, dreamAnalog);
        int chosenColumns = toggles.getInt(GRID_COLUMNS, 0);
        if (chosenColumns >= 4 && chosenColumns <= 7) gridColumns = chosenColumns;

        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        // A theme can also switch the wallpaper off while the file is still there.
        if (json != null && json.has("wallpaper") && !bool(json, "wallpaper", true)) wallpaper = null;

        return new Config(dock, hidden, liveTv, liveTvAction, panelService, panelAction, inputAction,
                Math.max(1, gridColumns),
                dreamAnalog, photos, playing, homePhoto, vpn,
                weatherLat, weatherLon, weatherPlace, homeSettings,
                jellyfinUrl, jellyfinKey, jellyfinUser, youtube, tv, youtubeKey, youtubeId, profiles, background,
                wallpaper, applied);
    }

    /**
     * The values for the named profile, else the first one, merged with the flat keys: the profile's
     * Jellyfin user and YouTube channel id win, and the YouTube channels of both apply. Without profiles,
     * the flat keys alone.
     */
    Profile profile(String name) {
        return merge(profiles, name, new Profile("", jellyfinUserId, youtubeChannelId, youtubeChannels, "", tvChannels));
    }

    static Profile merge(List<Profile> profiles, String name, Profile flat) {
        Profile chosen = null;
        for (Profile p : profiles) if (p.name.equals(name)) chosen = p;
        if (chosen == null && !profiles.isEmpty()) chosen = profiles.get(0);
        if (chosen == null) return flat;
        return new Profile(chosen.name, chosen.jellyfinUserId.isEmpty() ? flat.jellyfinUserId : chosen.jellyfinUserId,
                chosen.youtubeChannelId.isEmpty() ? flat.youtubeChannelId : chosen.youtubeChannelId,
                union(flat.youtubeChannels, chosen.youtubeChannels), chosen.photo, union(flat.tvChannels, chosen.tvChannels));
    }

    /** a, then whatever of b it lacks, in order. */
    static List<String> union(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        for (String s : b) if (!out.contains(s)) out.add(s);
        return out;
    }

    /**
     * "profiles": [{"name": ..., "jellyfin_user_id": ..., "youtube_channel_id": ..., "youtube_channels": [...], "tv_channels": [...],
     * "photo": "https://..."}];
     * entries without a name are skipped, and so is a photo that isn't https.
     */
    static List<Profile> profiles(JSONObject o) {
        List<Profile> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("profiles");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null) continue;
            String name = string(p, "name", "");
            if (name.isEmpty()) continue;
            String photo = string(p, "photo", "");
            out.add(new Profile(name, string(p, "jellyfin_user_id", ""), string(p, "youtube_channel_id", ""),
                    stringList(p, "youtube_channels", new ArrayList<>()), photo.startsWith("https://") ? photo : "",
                    stringList(p, "tv_channels", new ArrayList<>())));
        }
        return out;
    }

    /** The profile chosen on the TV (Home's profile button), or "" before any choice. */
    static String chosenProfile(Context ctx) {
        return toggles(ctx).getString(PROFILE, "");
    }

    static void chooseProfile(Context ctx, String name) {
        toggles(ctx).edit().putString(PROFILE, name).apply();
    }

    static final String PROFILE = "profile";

    /** Keys of the switches in the Settings menu; the same names as in config.xml and config.json. */
    static final String DREAM_ANALOG = "dream_analog", GRID_COLUMNS = "grid_columns";
    private static final String SECTIONS = "sections";

    /** Home's sections as the owner ordered them on the TV, or the default order. */
    static List<String> sectionOrder(Context ctx) {
        String saved = toggles(ctx).getString(SECTIONS, "");
        return saved.isEmpty() ? Arrays.asList(MainActivity.SECTION_YOUTUBE, MainActivity.SECTION_TV,
                MainActivity.SECTION_JELLYFIN, MainActivity.SECTION_APPS) : Arrays.asList(saved.split(","));
    }

    static void saveSectionOrder(Context ctx, List<String> order) {
        toggles(ctx).edit().putString(SECTIONS, String.join(",", order)).apply();
    }

    private static SharedPreferences toggles(Context ctx) {
        return ctx.getSharedPreferences("toggles", Context.MODE_PRIVATE);
    }

    /** Saves a switch from the Settings menu; it is read on the next {@link #load}. */
    static void setToggle(Context ctx, String key, boolean on) {
        toggles(ctx).edit().putBoolean(key, on).apply();
    }

    /** Saves a number chosen in the Settings menu (apps per row); it is read on the next {@link #load}. */
    static void setChoice(Context ctx, String key, int value) {
        toggles(ctx).edit().putInt(key, value).commit();
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            return c | 0xFF000000;   // always opaque: the window behind is black anyway
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }
}
