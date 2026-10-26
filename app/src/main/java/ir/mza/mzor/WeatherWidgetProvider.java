package ir.mza.mzor;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

public class WeatherWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "ir.mza.mzor.WEATHER_WIDGET_REFRESH";
    private static final long FOUR_HOURS = 4L * 60L * 60L * 1000L;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        showCached(context);
        fetchAsync(context);
        schedule(context);
    }

    @Override
    public void onEnabled(Context context) {
        schedule(context);
    }

    @Override
    public void onDisabled(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(refreshIntent(context));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_REFRESH.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            showCached(context);
            fetchAsync(context);
            schedule(context);
        }
    }

    private static void showCached(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("mza_widget", Context.MODE_PRIVATE);
        bind(context, prefs.getString("temp", "—"), prefs.getString("place", "موقعیت"), prefs.getInt("icon", R.drawable.widget_cloud));
    }

    private static void fetchAsync(Context context) {
        new Thread(() -> {
            try {
                Location location = lastLocation(context);
                if (location == null) {
                    bind(context, "—", "مکان بده", R.drawable.widget_cloud);
                    return;
                }
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + location.getLatitude()
                        + "&longitude=" + location.getLongitude()
                        + "&current=temperature_2m,weather_code&timezone=auto";
                JSONObject data = getJson(url);
                JSONObject current = data.getJSONObject("current");
                int temp = (int) Math.round(current.getDouble("temperature_2m"));
                int code = current.optInt("weather_code", 0);
                String place = city(location.getLatitude(), location.getLongitude());
                int icon = iconFor(code, temp);
                context.getSharedPreferences("mza_widget", Context.MODE_PRIVATE).edit()
                        .putString("temp", TimeWidgetProvider.fa(temp + "°"))
                        .putString("place", place)
                        .putInt("icon", icon)
                        .apply();
                bind(context, TimeWidgetProvider.fa(temp + "°"), place, icon);
            } catch (Exception ignored) {
                showCached(context);
            }
        }).start();
    }

    private static void bind(Context context, String temp, String place, int icon) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
        views.setTextViewText(R.id.weatherTemp, temp);
        views.setTextViewText(R.id.weatherPlace, place);
        views.setImageViewResource(R.id.weatherArt, icon);
        Intent open = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.weatherRoot, PendingIntent.getActivity(
                context, 12, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    private static int iconFor(int code, int temp) {
        if (code >= 95) return R.drawable.widget_bolt;
        if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86) || temp <= 0) return R.drawable.widget_snow;
        if (code <= 1) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return hour >= 6 && hour < 18 ? R.drawable.widget_sun : R.drawable.widget_moon;
        }
        return R.drawable.widget_cloud;
    }

    private static String city(double lat, double lng) {
        try {
            JSONObject geo = getJson("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude="
                    + lat + "&longitude=" + lng + "&localityLanguage=fa");
            String city = geo.optString("city", "");
            if (city.isEmpty()) city = geo.optString("locality", "");
            if (city.isEmpty()) city = geo.optString("principalSubdivision", "");
            return city.isEmpty() ? "موقعیت شما" : city;
        } catch (Exception e) {
            return "موقعیت شما";
        }
    }

    private static JSONObject getJson(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "MzorWidget");
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder raw = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) raw.append(line);
        reader.close();
        return new JSONObject(raw.toString());
    }

    private static Location lastLocation(Context context) {
        boolean fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return null;
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return null;
        Location best = null;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location found = manager.getLastKnownLocation(provider);
                if (found != null && (best == null || found.getTime() > best.getTime())) best = found;
            } catch (Exception ignored) {}
        }
        return best;
    }

    private static void schedule(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long at = System.currentTimeMillis() + FOUR_HOURS;
        PendingIntent pending = refreshIntent(context);
        if (Build.VERSION.SDK_INT >= 23) alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, pending);
        else alarms.set(AlarmManager.RTC, at, pending);
    }

    private static PendingIntent refreshIntent(Context context) {
        Intent intent = new Intent(context, WeatherWidgetProvider.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, 43, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
