package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Visible-screen refresh only, cached data, bounded HTTPS reads and no LAN listener. */
final class BentoData {
    interface Listener { void changed(); }
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean busy;
    private long attempted;
    private String place = "";
    private Listener listener;
    String weatherSymbol="cloud";
    String temperature = "—", range = "—", condition = Ui.tr("No weather data");
    String usd = "—", eur = "—", ratesDate = Ui.tr("No exchange rate data");
    int usdDirection, eurDirection;
    long weatherAt, ratesAt;
    boolean weatherFailed, ratesFailed;

    BentoData(Context c) {
        cache = c.getSharedPreferences("bento-data", Context.MODE_PRIVATE);
        apply(cache.getString("weather", ""), cache.getString("rates", ""));
        weatherAt = cache.getLong("weatherAt", 0);
        ratesAt = cache.getLong("ratesAt", 0);
        place = cache.getString("place", "");
    }

    void listen(Listener value) { listener = value; }
    void refresh() { refresh(false); }
    void refresh(boolean force) {
        long now = System.currentTimeMillis();
        String coordinates = Prefs.latitude() + "," + Prefs.longitude();
        if (busy || !force && now >= attempted && now - attempted < 120000) return;
        boolean weather = force || weatherAt == 0 || now < weatherAt || now - weatherAt >= 3600000 || !place.equals(coordinates);
        boolean rates = false; // The reference UI has no exchange-rate widget; do not fetch unused data.
        if (!weather && !rates) return;
        if (!place.equals(coordinates)) {
            weatherAt = 0; temperature = "—"; range = "—"; condition = Ui.tr("No weather data");
            if (listener != null) listener.changed();
        }
        busy = true;
        attempted = now;
        // Snapshot preferences on the UI thread before handing work to the worker.
        String lat = Prefs.latitude(), lon = Prefs.longitude();
        worker.execute(() -> {
            String w = null, r = null;
            if (weather) try {
                w = read("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + lat + "&lon=" + lon);
                new JSONObject(w).getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0)
                        .getJSONObject("data").getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
            } catch (Exception e) { w = null; }
            if (rates) try {
                r = read("https://www.cbr-xml-daily.ru/daily_json.js");
                JSONObject values = new JSONObject(r).getJSONObject("Valute");
                values.getJSONObject("USD").getDouble("Value");
                values.getJSONObject("EUR").getDouble("Value");
            } catch (Exception e) { r = null; }
            final String weatherJson = w, ratesJson = r;
            main.post(() -> {
                long at = System.currentTimeMillis();
                SharedPreferences.Editor edit = cache.edit();
                if (weatherJson != null) { weatherAt = at; place = coordinates; edit.putString("weather",weatherJson).putLong("weatherAt",at).putString("place",place); }
                if (ratesJson != null) { ratesAt = at; edit.putString("rates",ratesJson).putLong("ratesAt",at); }
                weatherFailed = weather && weatherJson == null;
                ratesFailed = rates && ratesJson == null;
                edit.apply();
                apply(weatherJson, ratesJson);
                busy = false;
                if (listener != null) listener.changed();
            });
        });
    }

    void close() { listener = null; worker.shutdownNow(); }

    private static String read(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "FreeMyTV-Haier-Bento/1.0 (https://github.com/aleksejsaraev1998-hub/free-my-tv-haier)");
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP response");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (out.size() + n > 2 * 1024 * 1024) throw new java.io.IOException("Response too large");
                    out.write(buffer,0,n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }

    private void apply(String weather, String rates) {
        if (weather != null && !weather.isEmpty()) try {
            JSONArray series = new JSONObject(weather).getJSONObject("properties").getJSONArray("timeseries");
            JSONObject first = series.getJSONObject(0).getJSONObject("data");
            double value = first.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
            temperature = degrees(value);
            double lo = value, hi = value;
            // Upcoming 24 hours, not an invented daily observed minimum/maximum.
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT);
            parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            long start = parser.parse(series.getJSONObject(0).getString("time")).getTime();
            for (int i=0;i<series.length();i++) {
                JSONObject item = series.getJSONObject(i);
                long time = parser.parse(item.getString("time")).getTime();
                if (time > start + 24*3600000L) break;
                double t = item.getJSONObject("data").getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
                lo = Math.min(lo,t); hi = Math.max(hi,t);
            }
            range = degrees(lo) + " / " + degrees(hi);
            String code = first.optJSONObject("next_1_hours") == null ? "" : first.getJSONObject("next_1_hours").getJSONObject("summary").optString("symbol_code");
            weatherSymbol=code.contains("thunder")?"thunderstorm":code.contains("snow")||code.contains("sleet")?"snowflake":code.contains("rain")?"rainy":code.contains("fog")?"foggy":code.startsWith("clearsky")?(code.contains("night")?"clear_night":"sunny"):code.startsWith("fair")||code.startsWith("partlycloudy")?(code.contains("night")?"partly_cloudy_night":"partly_cloudy_day"):"cloud";
            if (code.contains("thunder")) condition = Ui.tr("Thunderstorm");
            else if (code.contains("snow") || code.contains("sleet")) condition = Ui.tr("Snow");
            else if (code.contains("rain")) condition = Ui.tr("Rain");
            else if (code.contains("fog")) condition = Ui.tr("Fog");
            else if (code.startsWith("clearsky") || code.startsWith("fair")) condition = Ui.tr("Clear");
            else condition = Ui.tr("Cloudy");
        } catch (Exception ignored) {}
        if (rates != null && !rates.isEmpty()) try {
            JSONObject json = new JSONObject(rates);
            JSONObject values = json.getJSONObject("Valute");
            JSONObject dollar = values.getJSONObject("USD"), euro = values.getJSONObject("EUR");
            double d = dollar.getDouble("Value")/dollar.getDouble("Nominal");
            double e = euro.getDouble("Value")/euro.getDouble("Nominal");
            usd = String.format(Ui.locale(), "%.2f",d); eur = String.format(Ui.locale(),"%.2f",e);
            usdDirection = Double.compare(d,dollar.getDouble("Previous")/dollar.getDouble("Nominal"));
            eurDirection = Double.compare(e,euro.getDouble("Previous")/euro.getDouble("Nominal"));
            String date = json.getString("Date");
            ratesDate = date.substring(8,10)+"."+date.substring(5,7);
        } catch (Exception ignored) {}
    }
    private static String degrees(double t) { long rounded = Math.round(t); return (rounded>0?"+":"")+rounded+"°"; }
}
