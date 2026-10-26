package ir.mza.mzor;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import java.util.Calendar;

public class TimeWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_TICK = "ir.mza.mzor.TIME_WIDGET_TICK";

    private static final String[] WEEKDAYS = {
            "", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه"
    };
    private static final String[] MONTHS = {
            "", "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refreshAll(context);
        scheduleTick(context);
    }

    @Override
    public void onEnabled(Context context) {
        scheduleTick(context);
    }

    @Override
    public void onDisabled(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(tickIntent(context));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TICK.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            refreshAll(context);
            scheduleTick(context);
        }
    }

    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, TimeWidgetProvider.class));
        RemoteViews views = buildViews(context);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    private static RemoteViews buildViews(Context context) {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int[] jalali = toJalali(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        boolean day = hour >= 6 && hour < 18;

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_time);
        views.setTextViewText(R.id.widgetDay, WEEKDAYS[now.get(Calendar.DAY_OF_WEEK)]);
        views.setTextViewText(R.id.widgetTime, fa(String.format("%02d:%02d", hour, now.get(Calendar.MINUTE))));
        views.setTextViewText(R.id.widgetDate, fa(jalali[2] + " " + MONTHS[jalali[1]]));
        views.setImageViewResource(R.id.widgetArt, day ? R.drawable.widget_sun : R.drawable.widget_moon);

        Intent open = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(
                context, 11, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }

    private static void scheduleTick(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Calendar next = Calendar.getInstance();
        next.set(Calendar.SECOND, 1);
        next.set(Calendar.MILLISECOND, 0);
        next.add(Calendar.MINUTE, 1);
        PendingIntent pending = tickIntent(context);
        long at = next.getTimeInMillis();
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, pending);
        } else if (Build.VERSION.SDK_INT >= 23) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pending);
        } else {
            alarms.setExact(AlarmManager.RTC, at, pending);
        }
    }

    private static PendingIntent tickIntent(Context context) {
        Intent intent = new Intent(context, TimeWidgetProvider.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String fa(String value) {
        char[] digits = {'۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c >= '0' && c <= '9' ? digits[c - '0'] : c);
        }
        return out.toString();
    }

    static int[] toJalali(int gy, int gm, int gd) {
        int[] gDays = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
        int gy2 = gm > 2 ? gy + 1 : gy;
        int days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gDays[gm - 1];
        int jy = -1595 + (33 * (days / 12053));
        days %= 12053;
        jy += 4 * (days / 1461);
        days %= 1461;
        if (days > 365) {
            jy += (days - 1) / 365;
            days = (days - 1) % 365;
        }
        int jm;
        int jd;
        if (days < 186) {
            jm = 1 + (days / 31);
            jd = 1 + (days % 31);
        } else {
            jm = 7 + ((days - 186) / 30);
            jd = 1 + ((days - 186) % 30);
        }
        return new int[]{jy, jm, jd};
    }
}
