package com.vasudev.swadeshistore;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

// the TEST-only debug menu; lives in src/debug so the release APK never gets it
public class DebugActivity extends Activity {

    private TextView logView;
    private TextView verboseBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DLog.i("DebugMenu", "debug menu opened");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(dp(18), dp(20), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("Debug menu (TEST build)");
        title.setTextColor(0xFFE8EAED);
        title.setTextSize(19);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText(infoText());
        info.setTextColor(0xFF9AA0A6);
        info.setTextSize(13);
        info.setPadding(0, dp(12), 0, dp(12));
        root.addView(info);

        verboseBtn = button("Toggle verbose logging (now: " + DLog.isVerbose() + ")");
        verboseBtn.setOnClickListener(v -> {
            DLog.setVerbose(!DLog.isVerbose());
            verboseBtn.setText("Toggle verbose logging (now: " + DLog.isVerbose() + ")");
        });
        root.addView(verboseBtn);

        TextView refresh = button("Refresh log");
        refresh.setOnClickListener(v -> refreshLog());
        root.addView(refresh);

        TextView clear = button("Clear log");
        clear.setOnClickListener(v -> { DLog.clear(); refreshLog(); });
        root.addView(clear);

        TextView crash = button("Trigger a test crash");
        crash.setOnClickListener(v -> {
            DLog.e("DebugMenu", "throwing a deliberate test crash");
            try {
                throw new RuntimeException("Deliberate test crash from the debug menu");
            } catch (RuntimeException e) {
                DLog.e("DebugMenu", "action failed\n" + android.util.Log.getStackTraceString(e));
                refreshLog();
            }
        });
        root.addView(crash);

        logView = new TextView(this);
        logView.setTextColor(0xFFB9F6CA);
        logView.setTextSize(11);
        logView.setPadding(0, dp(12), 0, dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshLog();
    }

    private void refreshLog() {
        String d = DLog.dump();
        logView.setText(d.isEmpty() ? "(log is empty)" : d);
    }

    private String infoText() {
        return "Application id : " + getPackageName()
                + "\nVersion : " + versionName()
                + "\nBuild type : " + BuildConfig.BUILD_TYPE
                + "\nDebuggable : " + BuildConfig.DEBUG
                + "\nAPI env : test"
                + "\nDevice : " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nABI : " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                + "\nCatalog apps : " + AppRepository.load(this).apps.size()
                + "\nStrictMode : ON (disk / network / leaks)";
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable e) { return "?"; }
    }

    private TextView button(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFE8EAED);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setBackgroundColor(0xFF2A2A2A);
        t.setContentDescription(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
