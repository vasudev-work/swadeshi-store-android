package com.vasudev.swadeshistore;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

// TEST-build logger: stamps the time and keeps a ring buffer the debug menu reads
public final class DLog {
    private static final int MAX = 400;
    private static final ArrayDeque<String> RING = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static boolean verbose = true;

    private DLog() { }

    public static void setVerbose(boolean v) { verbose = v; }
    public static boolean isVerbose() { return verbose; }

    public static String dump() {
        StringBuilder b = new StringBuilder();
        synchronized (RING) { for (String l : RING) b.append(l).append('\n'); }
        return b.toString();
    }

    public static void clear() { synchronized (RING) { RING.clear(); } }

    private static void put(int prio, String lvl, String tag, String msg) {
        if (!verbose) return;
        String line = FMT.format(new Date()) + " " + lvl + "/" + tag + ": " + msg;
        synchronized (RING) {
            RING.addLast(line);
            while (RING.size() > MAX) RING.removeFirst();
        }
        Log.println(prio, tag, msg);
    }

    public static void v(String tag, String msg) { put(Log.VERBOSE, "V", tag, msg); }
    public static void i(String tag, String msg) { put(Log.INFO, "I", tag, msg); }
    public static void e(String tag, String msg) { put(Log.ERROR, "E", tag, msg); }
}
