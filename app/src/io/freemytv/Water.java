package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Calendar;

/**
 * A reminder to drink water (config.properties "water_every", minutes; 0 = off), shown over whatever is on screen: a
 * small card at the top right for a few seconds, only with the screen on and between "water_from" and "water_to"
 * o'clock. It needs to draw over other apps: `appops set io.freemytv SYSTEM_ALERT_WINDOW allow` (done at install).
 */
public final class Water extends BroadcastReceiver {
    private static final long SHOW_MS = 8000;

    static int every() {
        try {
            return Math.max(0, Integer.parseInt(Config.WATER_EVERY.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Sets the repeating alarm (the same PendingIntent: calling it again only moves it). */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Water.class), PendingIntent.FLAG_IMMUTABLE);
        if (every() == 0 || am == null) {
            if (am != null) am.cancel(pi);
            return;
        }
        long interval = every() * 60_000L;
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + interval, interval, pi);
    }

    @Override public void onReceive(Context c, Intent intent) {
        if (every() == 0) return;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < hourOf(Config.WATER_FROM, 8) || hour >= hourOf(Config.WATER_TO, 23)) return;
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm == null || !pm.isInteractive()) return;      // the TV is off (or in standby): nobody to remind
        if (!Settings.canDrawOverlays(c)) {
            AppLog.w("water: can't draw over other apps (appops SYSTEM_ALERT_WINDOW)");
            return;
        }
        PendingResult done = goAsync();    // the card stays a few seconds, within the receiver's 10 s
        try {
            show(c.getApplicationContext(), done);
        } catch (RuntimeException e) {
            AppLog.w("water: " + e.getMessage());
            done.finish();
        }
    }

    private static int hourOf(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void show(Context c, PendingResult done) {
        Ui.init(c);
        WindowManager wm = c.getSystemService(WindowManager.class);
        float dp = c.getResources().getDisplayMetrics().density;
        TextView card = new TextView(c);
        card.setText(Ui.tr("💧 Time to drink water"));
        card.setTextColor(Color.WHITE);
        card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        int pad = Math.round(14 * dp);
        card.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD91C1C1E);
        bg.setCornerRadius(18 * dp);
        card.setBackground(bg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Never takes the remote's keys or the focus: what you're watching goes on as it was.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(32 * dp);
        lp.y = Math.round(32 * dp);
        wm.addView(card, lp);
        AppLog.w("water: reminder shown");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                wm.removeView(card);
            } finally {
                done.finish();
            }
        }, SHOW_MS);
    }
}
