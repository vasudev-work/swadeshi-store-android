package com.vasudev.swadeshistore;

// USER-build logger: does nothing, and R8 strips the calls anyway
public final class DLog {
    private DLog() { }
    public static void setVerbose(boolean v) { }
    public static boolean isVerbose() { return false; }
    public static String dump() { return ""; }
    public static void clear() { }
    public static void v(String tag, String msg) { }
    public static void i(String tag, String msg) { }
    public static void e(String tag, String msg) { }
}
