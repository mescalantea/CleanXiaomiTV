package com.majnioui.tv.weather;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A 7-day forecast from Open-Meteo (free, no account or key; only the coordinates are sent). The city
 * starts as the one in res/values/config.xml; Change city searches Open-Meteo's geocoding and remembers
 * the choice.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Weather";
    private static final String PREFS = "city";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Typeface font;
    private int accent;
    private SharedPreferences prefs;
    private String lat, lon, placeName;

    private TextView place, temp, condition, details, status, refresh;
    private LinearLayout days;
    private boolean loading;

    // City search panel
    private FrameLayout overlay;
    private EditText query;
    private LinearLayout results;
    private TextView searchStatus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        lat = prefs.getString("lat", getString(R.string.latitude));
        lon = prefs.getString("lon", getString(R.string.longitude));
        placeName = prefs.getString("name", getString(R.string.place));

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(accent & 0x00FFFFFF) | 0x66000000, 0xFF050607, 0xFF050607}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(80), dp(48), dp(80), dp(40));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Top row: the city on the left, Refresh and Change city on the right.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        place = text(18, 400, 0xB3FFFFFF);
        place.setLetterSpacing(0.25f);
        top.addView(place, new LinearLayout.LayoutParams(0, -2, 1f));
        refresh = pill(getString(R.string.weather_refresh), R.drawable.ic_refresh);
        refresh.setOnClickListener(v -> load(true));
        top.addView(refresh);
        TextView city = pill(getString(R.string.weather_change_city), R.drawable.ic_place);
        city.setOnClickListener(v -> openSearch());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = dp(14);
        top.addView(city, clp);
        col.addView(top);

        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        temp = text(120, 400, 0xFFFFFFFF);
        now.addView(temp);
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(32), 0, 0, 0);
        condition = text(34, 500, 0xFFFFFFFF);
        details = text(20, 400, 0xB3FFFFFF);
        side.addView(condition);
        side.addView(details);
        now.addView(side);
        col.addView(now);

        col.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        days = new LinearLayout(this);
        col.addView(days, new LinearLayout.LayoutParams(-1, -2));

        status = text(16, 400, 0x80FFFFFF);
        status.setPadding(0, dp(18), 0, 0);
        col.addView(status);

        buildSearch(root);
        setContentView(root);
        showPlace();
        refresh.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(false);
    }

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) closeSearch();
        else super.onBackPressed();
    }

    private void showPlace() {
        place.setText(placeName.toUpperCase(Locale.ROOT));
    }

    // ---- forecast -----------------------------------------------------------------------------

    private void load(boolean manual) {
        if (loading) return;
        loading = true;
        status.setText(manual ? R.string.weather_refreshing : R.string.weather_loading);
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day,relative_humidity_2m,wind_speed_10m,apparent_temperature"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=7&timezone=auto";
        new Thread(() -> {
            JSONObject json = null;
            try {
                json = new JSONObject(fetch(url));
            } catch (Exception e) {
                Log.w(TAG, "fetch: " + e);
            }
            final JSONObject result = json;
            main.post(() -> {
                loading = false;
                if (result == null) {
                    // Offline: keep whatever is on screen.
                    status.setText(R.string.weather_offline);
                    return;
                }
                try {
                    render(result);
                } catch (Exception e) {
                    Log.w(TAG, "render: " + e);
                    status.setText(R.string.weather_bad_answer);
                }
            });
        }).start();
    }

    private void render(JSONObject json) throws Exception {
        JSONObject cur = json.getJSONObject("current");
        int code = cur.getInt("weather_code");
        boolean day = cur.optInt("is_day", 1) == 1;
        temp.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
        condition.setText(icon(code, day) + "  " + (describe(code) == 0 ? "" : getString(describe(code))));
        details.setText(getString(R.string.weather_details, Math.round(cur.getDouble("apparent_temperature")),
                cur.getInt("relative_humidity_2m"), Math.round(cur.getDouble("wind_speed_10m"))));

        JSONObject daily = json.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.getDefault());
        days.removeAllViews();
        for (int i = 0; i < dates.length(); i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(8), dp(18), dp(8), dp(18));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(i == 0 ? (accent & 0x00FFFFFF) | 0x40000000 : 0x14FFFFFF);
            card.setBackground(bg);
            Date d = in.parse(dates.getString(i));
            TextView name = text(20, 500, 0xFFFFFFFF);
            name.setText(i == 0 ? getString(R.string.weather_today) : out.format(d));
            TextView ic = text(40, 400, 0xFFFFFFFF);
            ic.setText(icon(codes.getInt(i), true));
            ic.setPadding(0, dp(8), 0, dp(8));
            TextView range = text(20, 400, 0xFFFFFFFF);
            range.setText(Math.round(max.getDouble(i)) + "°  " + Math.round(min.getDouble(i)) + "°");
            card.addView(name);
            card.addView(ic);
            card.addView(range);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, i == dates.length() - 1 ? 0 : dp(8), 0);
            days.addView(card, lp);
        }
        status.setText(getString(R.string.weather_updated, new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date())));
    }

    // ---- city search --------------------------------------------------------------------------

    private void buildSearch(FrameLayout root) {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xE6050607);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(36), dp(28), dp(36), dp(28));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setCornerRadius(dp(28));
        pbg.setColor(0xFF15181D);
        pbg.setStroke(dp(1), 0x26FFFFFF);
        panel.setBackground(pbg);

        TextView title = text(28, 500, 0xFFFFFFFF);
        title.setText(R.string.weather_change_city);
        panel.addView(title);
        TextView hint = text(17, 400, 0x99FFFFFF);
        hint.setText(R.string.weather_search_hint);
        hint.setPadding(0, dp(6), 0, dp(20));
        panel.addView(hint);

        query = new EditText(this);
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        query.setTextColor(0xFFFFFFFF);
        query.setHintTextColor(0x66FFFFFF);
        query.setHint("e.g. Rabat, Marrakech, Paris");
        query.setTypeface(font);
        query.setFontVariationSettings("'wght' 500");
        query.setPadding(dp(20), dp(14), dp(20), dp(14));
        query.setBackground(fieldBackground());
        Drawable search = getDrawable(R.drawable.ic_search);
        search.setTint(0x99FFFFFF);
        search.setBounds(0, 0, dp(26), dp(26));
        query.setCompoundDrawablesRelative(search, null, null, null);
        query.setCompoundDrawablePadding(dp(12));
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                search(query.getText().toString());
                return true;
            }
            return false;
        });
        panel.addView(query, new LinearLayout.LayoutParams(-1, -2));

        searchStatus = text(17, 400, 0x99FFFFFF);
        searchStatus.setPadding(0, dp(18), 0, dp(8));
        panel.addView(searchStatus);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        panel.addView(results, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(640), -2, Gravity.CENTER);
        overlay.addView(panel, plp);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void openSearch() {
        overlay.setVisibility(View.VISIBLE);
        results.removeAllViews();
        searchStatus.setText("");
        query.setText("");
        query.requestFocus();
        main.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void closeSearch() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
        overlay.setVisibility(View.GONE);
        refresh.requestFocus();
    }

    private void search(String name) {
        name = name.trim();
        if (name.isEmpty()) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
        searchStatus.setText(R.string.weather_searching);
        results.removeAllViews();
        final String url;
        try {
            url = "https://geocoding-api.open-meteo.com/v1/search?count=4&language=" + Locale.getDefault().getLanguage() + "&format=json&name="
                    + URLEncoder.encode(name, "UTF-8");
        } catch (Exception e) {
            return;
        }
        new Thread(() -> {
            JSONArray found = null;
            try {
                found = new JSONObject(fetch(url)).optJSONArray("results");
                if (found == null) found = new JSONArray();
            } catch (Exception e) {
                Log.w(TAG, "geocode: " + e);
            }
            final JSONArray list = found;
            main.post(() -> showResults(list));
        }).start();
    }

    private void showResults(JSONArray list) {
        results.removeAllViews();
        if (list == null) {
            searchStatus.setText(R.string.weather_search_offline);
            return;
        }
        if (list.length() == 0) {
            searchStatus.setText(R.string.weather_no_city);
            return;
        }
        searchStatus.setText(R.string.weather_choose);
        View first = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            if (r == null) continue;
            String city = r.optString("name");
            StringBuilder where = new StringBuilder();
            String admin = r.optString("admin1", "");
            String country = r.optString("country", "");
            if (!admin.isEmpty() && !admin.equals(city)) where.append(admin);
            if (!country.isEmpty()) where.append(where.length() > 0 ? ", " : "").append(country);
            TextView row = pill(city + (where.length() > 0 ? "  ·  " + where : ""), R.drawable.ic_place);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.setPadding(dp(20), dp(10), dp(20), dp(10));
            final double la = r.optDouble("latitude"), lo = r.optDouble("longitude");
            row.setOnClickListener(v -> choose(city, la, lo));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            results.addView(row, lp);
            if (first == null) first = row;
        }
        if (first != null) first.requestFocus();
    }

    private void choose(String name, double la, double lo) {
        lat = String.format(Locale.ROOT, "%.4f", la);
        lon = String.format(Locale.ROOT, "%.4f", lo);
        placeName = name;
        prefs.edit().putString("lat", lat).putString("lon", lon).putString("name", name).apply();
        showPlace();
        closeSearch();
        loading = false;
        load(true);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A rounded button for the remote: translucent, white with dark text when focused. */
    private TextView pill(String label, int iconRes) {
        TextView t = text(19, 500, 0xFFFFFFFF);
        t.setText(label);
        t.setFocusable(true);
        t.setClickable(true);
        t.setSingleLine(true);
        t.setPadding(dp(22), dp(12), dp(24), dp(12));
        int[][] states = {{android.R.attr.state_focused}, {}};
        ColorStateList fg = new ColorStateList(states, new int[] {0xFF0B0D10, 0xFFFFFFFF});
        t.setTextColor(fg);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(30));
        on.setColor(0xFFFFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(dp(30));
        off.setColor(0x1FFFFFFF);
        off.setStroke(dp(1), 0x33FFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        t.setBackground(bg);
        Drawable ic = getDrawable(iconRes).mutate();
        ic.setBounds(0, 0, dp(22), dp(22));
        t.setCompoundDrawablesRelative(ic, null, null, null);
        t.setCompoundDrawablePadding(dp(10));
        t.setCompoundDrawableTintList(fg);
        return t;
    }

    private Drawable fieldBackground() {
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(18));
        on.setColor(0x1AFFFFFF);
        on.setStroke(dp(2), accent);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(dp(18));
        off.setColor(0x14FFFFFF);
        off.setStroke(dp(1), 0x33FFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        return bg;
    }

    /** WMO weather code (as used by Open-Meteo) to an emoji. */
    private static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        // Fog as a cloud: older Android emoji fonts draw the fog emoji as a plain grey box.
        if (code == 45 || code == 48) return "☁️";
        if (code >= 51 && code <= 57) return "🌦️";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        return "🌧️";
    }

    /** The WMO code's description, or 0 when it has none. */
    private static int describe(int code) {
        if (code == 0) return R.string.w_clear;
        if (code == 1) return R.string.w_mostly_clear;
        if (code == 2) return R.string.w_partly_cloudy;
        if (code == 3) return R.string.w_overcast;
        if (code == 45 || code == 48) return R.string.w_fog;
        if (code >= 51 && code <= 57) return R.string.w_drizzle;
        if (code >= 61 && code <= 67) return R.string.w_rain;
        if (code >= 71 && code <= 77) return R.string.w_snow;
        if (code >= 80 && code <= 82) return R.string.w_showers;
        if (code == 85 || code == 86) return R.string.w_snow_showers;
        if (code >= 95) return R.string.w_thunderstorm;
        return 0;
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        // Outfit is a variable font: its weight comes from the wght axis, which Typeface.create leaves at Thin.
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
