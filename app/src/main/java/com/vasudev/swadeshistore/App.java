package com.vasudev.swadeshistore;

import android.app.Application;
import android.content.Context;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            try {
                Class.forName("com.vasudev.swadeshistore.DebugBootstrap")
                        .getMethod("init", Context.class).invoke(null, this);
            } catch (Throwable ignored) { }
        }
    }
}
