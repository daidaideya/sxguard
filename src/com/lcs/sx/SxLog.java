package com.lcs.sx;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 全局日志缓冲 + 配置存储 */
public class SxLog {

    public interface Listener {
        void onLog(String line);
    }

    private static final int MAX = 400;
    private static final List<String> LINES = new ArrayList<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    public static void log(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        try { android.util.Log.i("SxGuard", msg); } catch (Throwable ignore) { }
        synchronized (LINES) {
            LINES.add(line);
            if (LINES.size() > MAX) LINES.remove(0);
        }
        synchronized (LISTENERS) {
            for (Listener l : new ArrayList<>(LISTENERS)) {
                try { l.onLog(line); } catch (Throwable ignore) { }
            }
        }
    }

    public static void addListener(Listener l) {
        synchronized (LISTENERS) { LISTENERS.add(l); }
    }

    public static void removeListener(Listener l) {
        synchronized (LISTENERS) { LISTENERS.remove(l); }
    }

    public static List<String> snapshot() {
        synchronized (LINES) { return new ArrayList<>(LINES); }
    }

    // ------------------------------------------------------------ 配置
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("sx", Context.MODE_PRIVATE);
    }

    public static String get(Context c, String key, String def) {
        return sp(c).getString(key, def);
    }

    public static void set(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }

    public static boolean getBool(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    public static void setBool(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }
}
