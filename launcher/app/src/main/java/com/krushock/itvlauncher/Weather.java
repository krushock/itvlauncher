package com.krushock.itvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for one city, from Open-Meteo (free, no account or key). Only the city's coordinates are sent.
 * Fetched at most every 30 minutes while Home is on screen; no background work. Offline it doesn't try, keeps
 * showing the last reading for up to 3 hours, then hides it; a failed fetch is retried after 5 minutes, or as soon
 * as the network comes back.
 */
final class Weather {

    enum CitySearch { FOUND, NOT_FOUND, OFFLINE }

    /** One match for a city name: "Lisbon", "Lisbon District", "Portugal". */
    static final class Place {
        final String name, area, country;
        final double lat, lon;

        Place(String name, String area, String country, double lat, double lon) {
            this.name = name;
            this.area = area;
            this.country = country;
            this.lat = lat;
            this.lon = lon;
        }

        /** "Lisbon, Portugal" */
        String title() {
            return country.isEmpty() ? name : name + ", " + country;
        }
    }

    private static final long FRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final long SHOW_FOR_MS = 3 * 60 * 60_000L;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastAttempt;
    private boolean fetching;

    Weather(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    boolean hasCity() {
        return prefs.contains("lat");
    }

    String city() {
        return prefs.getString("city", null);
    }

    /** The chosen place with its country, "Lisbon, Portugal" (just the city for one saved before countries). */
    String cityLabel() {
        String city = city(), country = prefs.getString("country", "");
        return city == null || country.isEmpty() ? city : city + ", " + country;
    }

    /** °F where the TV's region uses it (US and a few others), unless the user picked a unit. */
    boolean fahrenheit() {
        if (prefs.contains("fahrenheit")) return prefs.getBoolean("fahrenheit", false);
        String c = Locale.getDefault().getCountry();
        return c.equals("US") || c.equals("LR") || c.equals("MM") || c.equals("BS") || c.equals("KY") || c.equals("PW");
    }

    void setFahrenheit(boolean f, Runnable updated) {
        prefs.edit().putBoolean("fahrenheit", f).remove("fetched").apply();
        lastAttempt = 0;
        refresh(updated);
    }

    /** "14°", or null when there's nothing current enough to show. */
    String label() {
        if (!prefs.contains("temp") || System.currentTimeMillis() - prefs.getLong("fetched", 0) > SHOW_FOR_MS) return null;
        return Math.round(prefs.getFloat("temp", 0)) + "°";
    }

    /** Icon for the last reading (WMO weather codes). */
    int icon() {
        int code = prefs.getInt("code", 0);
        boolean day = prefs.getBoolean("day", true);
        if (code >= 95) return R.drawable.ic_w_storm;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_w_rain;
        if (code >= 2) return R.drawable.ic_w_cloud;            // partly cloudy, overcast, fog
        return day ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
    }

    /** Fetches if the reading is older than 30 minutes (5 after a failure); {@code updated} runs on the main thread. */
    void refreshIfStale(Runnable updated) {
        if (!hasCity() || fetching) return;
        long now = System.currentTimeMillis();
        boolean stale = now - prefs.getLong("fetched", 0) > FRESH_MS;
        if (!stale || now - lastAttempt < RETRY_MS) return;
        refresh(updated);
    }

    /** The network just came back: don't wait for the 5-minute retry. */
    void onOnline(Runnable updated) {
        lastAttempt = 0;
        refreshIfStale(updated);
    }

    private void refresh(Runnable updated) {
        if (!hasCity() || fetching || !online()) return;
        fetching = true;
        lastAttempt = System.currentTimeMillis();
        float lat = prefs.getFloat("lat", 0), lon = prefs.getFloat("lon", 0);
        boolean f = fahrenheit();
        worker.execute(() -> {
            try {
                JSONObject cur = new JSONObject(get(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code,is_day&temperature_unit=%s&timezone=auto",
                        lat, lon, f ? "fahrenheit" : "celsius"))).getJSONObject("current");
                prefs.edit()
                        .putFloat("temp", (float) cur.getDouble("temperature_2m"))
                        .putInt("code", cur.getInt("weather_code"))
                        .putBoolean("day", cur.optInt("is_day", 1) == 1)
                        .putLong("fetched", System.currentTimeMillis())
                        .apply();
            } catch (Exception ignored) {
                // Offline, timed out or an unexpected answer: keep the last reading; retried in 5 minutes.
            }
            main.post(() -> {
                fetching = false;
                updated.run();
            });
        });
    }

    /**
     * Places matching a city name (Open-Meteo geocoding), most prominent first: "Lisbon" gives Lisbon, Portugal and
     * several towns in the US. Up to six, for the person to choose from; {@code done} runs on the main thread.
     */
    void search(String name, BiConsumer<CitySearch, List<Place>> done) {
        if (!online()) {
            done.accept(CitySearch.OFFLINE, new ArrayList<>());
            return;
        }
        worker.execute(() -> {
            CitySearch result;
            List<Place> places = new ArrayList<>();
            try {
                String lang = Locale.getDefault().getLanguage();
                JSONArray results = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=6&format=json"
                        + "&language=" + lang + "&name=" + URLEncoder.encode(name, "UTF-8"))).optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    places.add(new Place(r.getString("name"), r.optString("admin1", ""), r.optString("country", ""),
                            r.getDouble("latitude"), r.getDouble("longitude")));
                }
                result = places.isEmpty() ? CitySearch.NOT_FOUND : CitySearch.FOUND;
            } catch (Exception e) {
                result = CitySearch.OFFLINE;
            }
            CitySearch r = result;
            main.post(() -> done.accept(r, places));
        });
    }

    /** Saves the chosen place and fetches its weather. */
    void useCity(Place p, Runnable updated) {
        prefs.edit()
                .putString("city", p.name)
                .putString("country", p.country)
                .putFloat("lat", (float) p.lat)
                .putFloat("lon", (float) p.lon)
                .remove("fetched").remove("temp")
                .apply();
        lastAttempt = 0;
        refresh(updated);
    }

    void clearCity(Runnable updated) {
        prefs.edit().clear().apply();
        updated.run();
    }

    private boolean online() {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
