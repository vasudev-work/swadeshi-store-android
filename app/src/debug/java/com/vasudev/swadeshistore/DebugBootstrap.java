package com.vasudev.swadeshistore;

import android.content.Context;
import android.os.StrictMode;

// turns on the TEST-build safety nets; only the debug variant loads this
public final class DebugBootstrap {
    private DebugBootstrap() { }

    public static void init(Context ctx) {
        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build());
        } catch (Throwable ignored) { }
        DLog.i("App", "debug bootstrap on, StrictMode armed");
    }
}
