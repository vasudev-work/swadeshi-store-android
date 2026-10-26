package com.vasudev.swadeshistore;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Swadeshi Appstore - a Play-Store-style storefront for Made-in-India apps. */
public class MainActivity extends Activity {

    // theme colours; swapped at runtime when you change the appearance setting
    static int BG = 0xFF121212, SURFACE = 0xFF1E1E1E, SURFACE2 = 0xFF2A2A2A,
            SURFACE3 = 0xFF303134, TEXT = 0xFFE8EAED, MUTED = 0xFF9AA0A6, BORDER = 0xFF2C2C2C,
            GREEN = 0xFF01875F, GREEN2 = 0xFF00A173, SAFFRON = 0xFFFF9800, RED = 0xFFEA4335;

    // appearance
    static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    /** accent presets: {primary, primaryVariant} */
    static final int[][] ACCENTS = {
            {0xFF01875F, 0xFF00A173},   // Swadeshi green
            {0xFFEF6C00, 0xFFFB8C00},   // Saffron
            {0xFF1A237E, 0xFF283593}    // Indigo
    };
    static final String[] ACCENT_NAMES = {"Green", "Saffron", "Indigo"};
    private int themeMode = THEME_DARK;
    private int accentIdx = 0;
    private long lastBackPress = 0;
    private TextView offlineBar;

    private AppRepository repo;
    private SharedPreferences prefs;
    private final Set<String> saved = new HashSet<>();
    private final LruCache<String, Bitmap> iconCache = new LruCache<>(256);
    // icons decode on a background thread so the list never stutters
    private final java.util.concurrent.ExecutorService iconPool =
            java.util.concurrent.Executors.newFixedThreadPool(3);

    private LinearLayout view, chipsRow, navRow, manageOverlay, manageBody, govtOverlay, loginOverlay;
    private FrameLayout rootView;
    private LinearLayout devOverlay, devBody, allOverlay, allBody, settingsOverlay, settingsBody;
    private TextView devTitle, allTitle;
    private String devOpen = null;
    private TextView govtTitle;
    private EditText lname, lmail;
    private TextView selBar;
    private int selCount = 0;
    private String manageFilter = "this";
    private java.util.Set<String> installedKeys = null;
    private String userName = "", userEmail = "";
    private android.webkit.WebView webView;
    private TextView overviewTab, manageTab;
    private String tab = "apps", chip = "top", query = "", category = null, mtab = "overview";
    private boolean showAll = false;
    private final android.os.Handler searchHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable searchRunnable = () -> { try { drawResults(); } catch (Throwable e) { crashLog(e); } };
    private final java.util.HashMap<String, android.graphics.drawable.Drawable> installedIconCache = new java.util.HashMap<>();
    private int manageToken = 0;
    static final String DEV_EMAIL = "vasudev.work@rediffmail.com";
    static final String GITHUB_PROFILE = "https://github.com/vasudev-work";
    // where the optional tip goes; swap in your real UPI id
    static final String UPI_ID = "vasudevupi@upi";
    static final String UPI_PAYEE = "Vasudev";
    private static final int TIP_MIN = 100;
    private static final int MAX_RESULTS = 60;
    // left blank on purpose — the app is fully offline, so a tampered remote file can't
    // inject listings or URLs. Put a URL here to turn the update channel back on, but
    // check the entries (https, allowed hosts) before trusting them.
    static final String REMOTE_ADDITIONS_URL = "";
    static final String ADDITIONS_FILE = "additions.json";
    private final java.util.Set<String> localIds = new java.util.HashSet<>();
    private static final int MAX_MANAGE_ROWS = 120;

    private static final String[] SUGGESTED = {"Zomato", "Swiggy", "Angel One", "PhonePe", "Flipkart", "Zoho Mail"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.File dir = getExternalFilesDir(null);
                if (dir != null) {
                    java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "last_crash.txt"));
                    w.write(android.util.Log.getStackTraceString(e));
                    w.close();
                }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        saved.addAll(prefs.getStringSet("saved", new HashSet<>()));
        userName = prefs.getString("user_name", "");
        userEmail = prefs.getString("user_email", "");
        themeMode = prefs.getInt("theme", THEME_DARK);
        accentIdx = prefs.getInt("accent", 0);
        applyPalette();
        repo = AppRepository.load(this);
        loadLocalAdditions();
        loadCachedAdditions();
        buildUi();
        registerNetworkWatch();
        fetchRemoteAdditions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateOfflineBar();
    }

    // watch the connection so the offline bar shows and hides by itself
    private void registerNetworkWatch() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 24) return;
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) {
                    runOnUiThread(() -> updateOfflineBar());
                }
                @Override public void onLost(android.net.Network n) {
                    runOnUiThread(() -> updateOfflineBar());
                }
            });
        } catch (Throwable ignored) { }
    }

    private boolean isOnline() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null
                        && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            @SuppressWarnings("deprecation")
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable e) { return true; }
    }

    private void updateOfflineBar() {
        if (offlineBar == null) return;
        try { offlineBar.setVisibility(isOnline() ? View.GONE : View.VISIBLE); }
        catch (Throwable ignored) { }
    }

    @Override
    public void onBackPressed() {
        if (allOverlay != null && allOverlay.getVisibility() == View.VISIBLE) {
            allOverlay.setVisibility(View.GONE);
            return;
        }
        if (devOverlay != null && devOverlay.getVisibility() == View.VISIBLE) {
            devOverlay.setVisibility(View.GONE);
            return;
        }
        if (manageOverlay != null && manageOverlay.getVisibility() == View.VISIBLE) {
            manageOverlay.setVisibility(View.GONE);
            return;
        }
        if (govtOverlay != null && govtOverlay.getVisibility() == View.VISIBLE) {
            if (webView != null && webView.canGoBack()) { webView.goBack(); return; }
            tab = "apps"; chip = "top"; category = null; render();
            return;
        }
        if (!"apps".equals(tab)) { tab = "apps"; chip = "top"; category = null; render(); return; }
        if (category != null) { category = null; render(); return; }
        if (!query.isEmpty()) { query = ""; render(); return; }
        // nothing left to close — press back twice to exit
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            toast("Press back again to exit Swadeshi Appstore");
        }
    }

    private void saveUser() {
        String nm = lname.getText().toString().trim();
        String em = lmail.getText().toString().trim();
        if (nm.isEmpty()) nm = "Guest";
        userName = nm; userEmail = em;
        prefs.edit().putString("user_name", nm).putString("user_email", em).apply();
        loginOverlay.setVisibility(View.GONE);
        render();
    }
    private String initialsOf(String n) {
        if (n == null || n.trim().isEmpty()) return "G";
        String[] p = n.trim().split("\\s+");
        String r = p[0].substring(0, 1);
        if (p.length > 1 && !p[1].isEmpty()) r += p[1].substring(0, 1);
        return r.toUpperCase(Locale.US);
    }

    // ---------------- helpers ----------------
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView tv(String s, float sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        return t;
    }
    private TextView bold(TextView t) { t.setTypeface(t.getTypeface(), Typeface.BOLD); return t; }
    private GradientDrawable bg(int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }
    private int appColor(AppItem a) {
        try { return Color.parseColor(a.color); } catch (Exception e) { return 0xFF1565C0; }
    }
    private static String letterOf(String n) {
        n = n == null ? "" : n.trim();
        return n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase(Locale.US);
    }
    private boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private Bitmap iconBitmap(String id) {
        Bitmap b = iconCache.get(id);
        if (b != null) return b;
        try (java.io.InputStream is = getAssets().open("logos/" + id + ".png")) {
            b = BitmapFactory.decodeStream(is);
            if (b != null) iconCache.put(id, b);
        } catch (Exception ignored) { }
        return b;
    }
    // app icon; shows a coloured initial until the real logo loads
    private View iconView(final AppItem a, int sizeDp) {
        final FrameLayout box = new FrameLayout(this);
        final TextView mono = bold(tv(letterOf(a.name), sizeDp * 0.42f, 0xFFFFFFFF));
        mono.setGravity(Gravity.CENTER);
        mono.setBackground(bg(appColor(a), sizeDp * 0.26f, 0, 0));
        box.addView(mono, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));

        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(sizeDp * 0.26f));
        iv.setBackground(g);
        iv.setClipToOutline(true);
        box.addView(iv, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));

        Bitmap b = iconCache.get(a.id);
        if (b != null) {
            mono.setVisibility(View.GONE);
            iv.setImageBitmap(b);
        } else {
            final String id = a.id;
            try {
                iconPool.execute(() -> {
                    final Bitmap loaded = decodeIcon(id);
                    if (loaded != null) {
                        iconCache.put(id, loaded);
                        iv.post(() -> {
                            try { mono.setVisibility(View.GONE); iv.setImageBitmap(loaded); }
                            catch (Throwable ignored) { }
                        });
                    }
                });
            } catch (Throwable ignored) { }
        }
        box.setContentDescription(a.name);
        return box;
    }

    // decode a single logo from assets (background thread)
    private Bitmap decodeIcon(String id) {
        try (java.io.InputStream is = getAssets().open("logos/" + id + ".png")) {
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            return null;
        }
    }
    private void openUrl(String url) {
        if (!notEmpty(url)) { toast("Link not available for this app"); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { toast("No app available to open this link"); }
    }
    private void persist() { prefs.edit().putStringSet("saved", new HashSet<>(saved)).apply(); }
    private void toggleSave(String id) {
        if (saved.contains(id)) saved.remove(id); else saved.add(id);
        persist(); render();
    }

    // ---------------- appearance ----------------
    private boolean isDarkNow() {
        if (themeMode == THEME_LIGHT) return false;
        if (themeMode == THEME_DARK) return true;
        int m = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyPalette() {
        if (isDarkNow()) {
            BG = 0xFF121212; SURFACE = 0xFF1E1E1E; SURFACE2 = 0xFF2A2A2A;
            SURFACE3 = 0xFF303134; TEXT = 0xFFE8EAED; MUTED = 0xFFADB2B8; BORDER = 0xFF2C2C2C;
        } else {
            BG = 0xFFF6F7F9; SURFACE = 0xFFFFFFFF; SURFACE2 = 0xFFEFF1F4;
            SURFACE3 = 0xFFE6E8EC; TEXT = 0xFF1A1B1E; MUTED = 0xFF4A4E54; BORDER = 0xFFE1E3E7;
        }
        int a = Math.max(0, Math.min(ACCENTS.length - 1, accentIdx));
        GREEN = ACCENTS[a][0];
        GREEN2 = ACCENTS[a][1];
        try {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(isDarkNow() ? 0xFF1E1E1E : 0xFFFFFFFF);
        } catch (Throwable ignored) { }
    }

    private void rebuildForAppearance() {
        applyPalette();
        tab = "you"; chip = "top"; category = null;
        buildUi();
        openSettings();
    }

    private void openSettings() {
        if (settingsOverlay == null) return;
        renderSettings();
        settingsOverlay.setVisibility(View.VISIBLE);
    }

    private void renderSettings() {
        if (settingsBody == null) return;
        settingsBody.removeAllViews();

        settingsBody.addView(section("Appearance"));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(bg(SURFACE, 16, BORDER, 1));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(14), dp(4), dp(14), dp(4));
        card.setLayoutParams(clp);

        card.addView(bold(tv("Theme", 15, TEXT)));
        TextView themeSub = tv("System follows your device setting", 12.5f, MUTED);
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tslp.bottomMargin = dp(10);
        card.addView(themeSub, tslp);

        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] themeNames = {"System", "Light", "Dark"};
        for (int i = 0; i < themeNames.length; i++) {
            final int mode = i;
            boolean on = themeMode == mode;
            TextView chip = bold(tv(themeNames[i], 13.5f, on ? 0xFFFFFFFF : TEXT));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(12), dp(9), dp(12), dp(9));
            chip.setBackground(on ? bg(GREEN, 12, GREEN, 1) : bg(SURFACE2, 12, BORDER, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < themeNames.length - 1) lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setContentDescription(themeNames[i] + " theme");
            chip.setOnClickListener(v -> {
                if (themeMode != mode) {
                    themeMode = mode;
                    prefs.edit().putInt("theme", mode).apply();
                    rebuildForAppearance();
                }
            });
            themeRow.addView(chip);
        }
        card.addView(themeRow);

        TextView accentLbl = bold(tv("Accent colour", 15, TEXT));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(18);
        card.addView(accentLbl, alp);

        LinearLayout accentRow = new LinearLayout(this);
        accentRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = dp(10);
        accentRow.setLayoutParams(arlp);
        for (int i = 0; i < ACCENTS.length; i++) {
            final int idx = i;
            boolean on = accentIdx == idx;
            TextView chip = bold(tv(ACCENT_NAMES[i], 13.5f, 0xFFFFFFFF));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(12), dp(9), dp(12), dp(9));
            chip.setBackground(on ? bg(ACCENTS[i][0], 12, TEXT, 2) : bg(ACCENTS[i][0], 12, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < ACCENTS.length - 1) lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setContentDescription(ACCENT_NAMES[i] + " accent");
            chip.setOnClickListener(v -> {
                if (accentIdx != idx) {
                    accentIdx = idx;
                    prefs.edit().putInt("accent", idx).apply();
                    rebuildForAppearance();
                }
            });
            accentRow.addView(chip);
        }
        card.addView(accentRow);
        settingsBody.addView(card);

        // developer
        settingsBody.addView(section("Developer"));
        LinearLayout dev = new LinearLayout(this);
        dev.setOrientation(LinearLayout.VERTICAL);
        dev.setBackground(bg(SURFACE, 16, BORDER, 1));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(dp(14), dp(4), dp(14), dp(16));
        dev.setLayoutParams(dlp);

        LinearLayout dhead = new LinearLayout(this);
        dhead.setOrientation(LinearLayout.HORIZONTAL);
        dhead.setGravity(Gravity.CENTER_VERTICAL);
        dhead.setPadding(dp(14), dp(14), dp(14), dp(14));
        TextView av = bold(tv("VD", 18, 0xFFFFFFFF));
        av.setGravity(Gravity.CENTER);
        av.setBackground(bg(GREEN, 14, 0, 0));
        dhead.addView(av, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout dcol = new LinearLayout(this);
        dcol.setOrientation(LinearLayout.VERTICAL);
        dcol.addView(bold(tv("Vasudev Dangaya", 16, TEXT)));
        dcol.addView(tv("Independent developer \u00b7 India", 12.5f, MUTED));
        LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dclp.leftMargin = dp(14);
        dhead.addView(dcol, dclp);
        dev.addView(dhead);

        dev.addView(devRow("Email", DEV_EMAIL, v -> {
            try {
                startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + DEV_EMAIL)));
            } catch (Throwable e) { toast("No email app found"); }
        }));
        dev.addView(devRow("GitHub", "github.com/vasudev-work", v -> openUrl(GITHUB_PROFILE)));

        settingsBody.addView(dev);
    }

    private View devRow(String label, String value, View.OnClickListener click) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(label, 11, MUTED));
        col.addView(bold(tv(value, 14, TEXT)));
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(tv("\u203A", 20, MUTED));
        r.setContentDescription(label + ": " + value);
        r.setOnClickListener(click);
        wrap.addView(r);
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        wrap.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return wrap;
    }

    // ---------------- layout ----------------
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(58));
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(12), dp(8), dp(2));
        TextView title = bold(tv("Swadeshi Appstore", 20, TEXT));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout bellBox = new FrameLayout(this);
        ImageView bell = new ImageView(this);
        bell.setImageResource(R.drawable.ic_bell);
        bell.setColorFilter(TEXT);
        bellBox.addView(bell, new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
        LinearLayout.LayoutParams bellLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        bellBox.setContentDescription("Your activity");
        bellBox.setOnClickListener(v -> showUpdates());
        bar.addView(bellBox, bellLp);

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setColorFilter(TEXT);
        LinearLayout searchBtn = new LinearLayout(this);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.addView(searchIcon, new LinearLayout.LayoutParams(dp(23), dp(23)));
        searchBtn.setOnClickListener(v -> { tab = "search"; chip = "top"; category = null; render(); });
        bar.addView(searchBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));

        col.addView(bar);

        // offline bar
        offlineBar = tv("Offline \u00b7 showing the list saved on your device", 12, 0xFFFFFFFF);
        offlineBar.setBackgroundColor(0xFF5D4037);
        offlineBar.setPadding(dp(16), dp(7), dp(16), dp(7));
        offlineBar.setVisibility(View.GONE);
        col.addView(offlineBar);

        // chips
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(12), dp(8), dp(12), dp(10));
        hs.addView(chipsRow);
        col.addView(hs);

        // scroll content
        ScrollView sv = new ScrollView(this);
        view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(0, 0, 0, dp(20));
        sv.addView(view);
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // bottom nav
        navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setBackgroundColor(SURFACE);
        root.addView(navRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.BOTTOM));

        // manage overlay
        manageOverlay = new LinearLayout(this);
        manageOverlay.setOrientation(LinearLayout.VERTICAL);
        manageOverlay.setBackgroundColor(BG);
        manageOverlay.setVisibility(View.GONE);
        LinearLayout mhead = new LinearLayout(this);
        mhead.setOrientation(LinearLayout.HORIZONTAL);
        mhead.setGravity(Gravity.CENTER_VERTICAL);
        mhead.setPadding(dp(8), dp(12), dp(12), dp(12));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(TEXT);
        LinearLayout backBtn = new LinearLayout(this);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.addView(back, new LinearLayout.LayoutParams(dp(22), dp(22)));
        backBtn.setOnClickListener(v -> manageOverlay.setVisibility(View.GONE));
        mhead.addView(backBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        mhead.addView(bold(tv("Manage apps & device", 19, TEXT)));
        manageOverlay.addView(mhead);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(16), 0, dp(16), 0);
        overviewTab = bold(tv("Overview", 14, GREEN2));
        manageTab = bold(tv("Manage", 14, MUTED));
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t1.rightMargin = dp(26);
        overviewTab.setPadding(0, dp(12), 0, dp(12));
        manageTab.setPadding(0, dp(12), 0, dp(12));
        overviewTab.setOnClickListener(v -> { mtab = "overview"; renderManage(); });
        manageTab.setOnClickListener(v -> { mtab = "manage"; renderManage(); });
        tabs.addView(overviewTab, t1);
        tabs.addView(manageTab);
        manageOverlay.addView(tabs);

        ScrollView msv = new ScrollView(this);
        manageBody = new LinearLayout(this);
        manageBody.setOrientation(LinearLayout.VERTICAL);
        msv.addView(manageBody);
        manageOverlay.addView(msv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(manageOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // govt apps web container
        govtOverlay = new LinearLayout(this);
        govtOverlay.setOrientation(LinearLayout.VERTICAL);
        govtOverlay.setBackgroundColor(BG);
        govtOverlay.setVisibility(View.GONE);
        LinearLayout gh = new LinearLayout(this);
        gh.setOrientation(LinearLayout.HORIZONTAL);
        gh.setGravity(Gravity.CENTER_VERTICAL);
        gh.setPadding(dp(16), dp(12), dp(14), dp(6));
        govtTitle = bold(tv("Gov.in Appstore", 17, TEXT));
        gh.addView(govtTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView gopen = bold(tv("Open in browser \u2197", 12, GREEN2));
        gopen.setPadding(dp(12), dp(6), dp(12), dp(6));
        gopen.setBackground(bg(SURFACE, 999, BORDER, 1));
        gopen.setOnClickListener(v -> openUrl("https://apps.gov.in/All-Apps"));
        gh.addView(gopen);
        govtOverlay.addView(gh);
        TextView gnote = tv("Official government app store \u00b7 apps.gov.in/All-Apps", 12, MUTED);
        gnote.setPadding(dp(16), 0, dp(16), dp(10));
        govtOverlay.addView(gnote);
        webView = new android.webkit.WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onReceivedError(android.webkit.WebView v, android.webkit.WebResourceRequest r,
                                        android.webkit.WebResourceError e) {
                if (r != null && r.isForMainFrame()) toast("Could not load apps.gov.in \u2014 check your internet");
            }
            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(android.webkit.WebView v, int code, String desc, String url) {
                toast("Could not load apps.gov.in \u2014 check your internet");
            }
        });
        govtOverlay.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(govtOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // login overlay (all local, no verification)
        loginOverlay = new LinearLayout(this);
        loginOverlay.setOrientation(LinearLayout.VERTICAL);
        loginOverlay.setGravity(Gravity.CENTER);
        loginOverlay.setBackgroundColor(BG);
        loginOverlay.setPadding(dp(22), dp(22), dp(22), dp(22));
        loginOverlay.setVisibility(View.GONE);
        LinearLayout lcard = new LinearLayout(this);
        lcard.setOrientation(LinearLayout.VERTICAL);
        lcard.setPadding(dp(22), dp(26), dp(22), dp(20));
        lcard.setBackground(bg(SURFACE, 22, BORDER, 1));
        TextView llogo = bold(tv("S", 30, 0xFFFFFFFF));
        llogo.setGravity(Gravity.CENTER);
        llogo.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{SAFFRON, GREEN}));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(64), dp(64));
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        lcard.addView(llogo, llp);
        TextView lt = bold(tv("Swadeshi Appstore", 21, TEXT));
        lt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ltp.topMargin = dp(12);
        lcard.addView(lt, ltp);
        TextView ls = tv("Only Indian apps \u00b7 Made in India", 12.5f, MUTED);
        ls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lsp.topMargin = dp(4); lsp.bottomMargin = dp(16);
        lcard.addView(ls, lsp);
        lcard.addView(tv("YOUR NAME", 11, MUTED));
        lname = new EditText(this);
        lname.setHint("e.g. Vasudev");
        lname.setTextColor(TEXT); lname.setHintTextColor(MUTED);
        lname.setBackground(bg(SURFACE2, 12, BORDER, 1));
        lname.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams inp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inp.bottomMargin = dp(12);
        lcard.addView(lname, inp);
        lcard.addView(tv("EMAIL ADDRESS", 11, MUTED));
        lmail = new EditText(this);
        lmail.setHint("you@example.com");
        lmail.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        lmail.setTextColor(TEXT); lmail.setHintTextColor(MUTED);
        lmail.setBackground(bg(SURFACE2, 12, BORDER, 1));
        lmail.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams inp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inp2.bottomMargin = dp(14);
        lcard.addView(lmail, inp2);
        TextView lgo = bold(tv("Continue", 15, 0xFFFFFFFF));
        lgo.setGravity(Gravity.CENTER);
        lgo.setPadding(dp(14), dp(13), dp(14), dp(13));
        lgo.setBackground(bg(GREEN, 12, 0, 0));
        lgo.setOnClickListener(v -> saveUser());
        lcard.addView(lgo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView ln = tv("No OTP, no verification \u2014 your details are saved on this device only.", 11, MUTED);
        ln.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lnp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lnp.topMargin = dp(12);
        lcard.addView(ln, lnp);
        TextView skip = bold(tv("Skip for now", 13, MUTED));
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(dp(14), dp(12), dp(14), dp(4));
        skip.setContentDescription("Skip for now");
        LinearLayout.LayoutParams skp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        skp.topMargin = dp(6);
        skip.setLayoutParams(skp);
        skip.setOnClickListener(v -> {
            userName = "Guest"; userEmail = "";
            prefs.edit().putString("user_name", userName).putString("user_email", "").apply();
            loginOverlay.setVisibility(View.GONE);
            render();
        });
        lcard.addView(skip);
        loginOverlay.addView(lcard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(loginOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout[] made = makePage("Developer");
        devOverlay = made[0];
        devBody = made[1];
        devTitle = (TextView) made[0].getTag();
        made = makePage("All apps");
        allOverlay = made[0];
        allBody = made[1];
        allTitle = (TextView) made[0].getTag();
        root.addView(devOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(allOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        made = makePage("Settings");
        settingsOverlay = made[0];
        settingsBody = made[1];
        root.addView(settingsOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        render();
        if (userName == null || userName.isEmpty()) loginOverlay.setVisibility(View.VISIBLE);
    }

    // ---------------- nav ----------------
    private View navItem(String key, int icon, String label) {
        boolean on = key.equals(tab);
        int c = on ? GREEN2 : MUTED;
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);
        it.setPadding(0, dp(9), 0, dp(10));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(c);
        it.addView(iv, new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView t = bold(tv(label, 11, c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        it.addView(t, lp);
        it.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        it.setOnClickListener(v -> { tab = key; chip = "top"; category = null; showAll = false; render(); });
        return it;
    }
    private void renderNav() {
        navRow.removeAllViews();
        navRow.addView(navItem("govt", R.drawable.ic_govt, "Govt Apps"));
        navRow.addView(navItem("apps", R.drawable.ic_apps, "Apps"));
        navRow.addView(navItem("search", R.drawable.ic_search, "Search"));
        navRow.addView(navItem("books", R.drawable.ic_books, "Books"));
        navRow.addView(navItem("you", R.drawable.ic_you, "You"));
    }

    private void renderChips() {
        chipsRow.removeAllViews();
        String[][] defs = {{"top", "Top charts"}, {"govt", "Government"}, {"fintech", "Fintech"},
                {"social", "Social"}, {"games", "Games"}, {"alternatives", "Swadeshi Alternatives"},
                {"categories", "Categories"}};
        for (String[] d : defs) {
            boolean on = d[0].equals(chip);
            TextView c = bold(tv(d[1], 13, on ? 0xFFFFFFFF : TEXT));
            c.setPadding(dp(14), dp(7), dp(14), dp(7));
            c.setBackground(on ? bg(GREEN, 999, GREEN, 1) : bg(SURFACE, 999, BORDER, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            c.setLayoutParams(lp);
            final String key = d[0];
            c.setOnClickListener(v -> { chip = key; category = null; showAll = false; render(); });
            chipsRow.addView(c);
        }
    }

    // ---------------- content ----------------
    private void render() {
        try {
            renderNav();
            chipsRow.setVisibility("apps".equals(tab) ? View.VISIBLE : View.GONE);
            view.removeAllViews();
            if ("govt".equals(tab)) { renderGovt(); return; }
            govtOverlay.setVisibility(View.GONE);
            if ("apps".equals(tab)) { renderChips(); renderApps(); }
            else if ("books".equals(tab)) renderBooks();
            else if ("search".equals(tab)) renderSearch();
            else renderYou();
        } catch (Throwable e) {
            crashLog(e);
            try {
                view.removeAllViews();
                TextView h = bold(tv("Something went wrong", 16, TEXT));
                h.setPadding(dp(16), dp(24), dp(16), dp(6));
                view.addView(h);
                // never expose the raw exception or a stack trace to the user
                TextView d = tv("This screen could not be drawn. Your saved apps are safe on this device.",
                        12.5f, MUTED);
                d.setPadding(dp(16), 0, dp(16), dp(12));
                view.addView(d);
                TextView retry = bold(tv("Try again", 14, 0xFFFFFFFF));
                retry.setGravity(Gravity.CENTER);
                retry.setPadding(dp(14), dp(13), dp(14), dp(13));
                retry.setContentDescription("Try again");
                retry.setBackground(bg(GREEN, 12, 0, 0));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(dp(16), 0, dp(16), dp(16));
                retry.setLayoutParams(rlp);
                retry.setOnClickListener(v -> render());
                view.addView(retry);
            } catch (Throwable ignored) { }
        }
    }

    private List<AppItem> byCat(String c) {
        List<AppItem> out = new ArrayList<>();
        for (AppItem a : repo.apps) if (a.category.equals(c)) out.add(a);
        return out;
    }
    private AppItem find(String id) {
        for (AppItem a : repo.apps) if (a.id.equals(id)) return a;
        return null;
    }

    // ---- Swadeshi alternative recommendation ----
    private static final String[][] ALTS = {
        {"whatsapp|telegram|signal|messenger|wechat", "WhatsApp / Telegram", "Arattai", "JioChat|JioSafe|Sandes (GIMS)|Elyments|TroopMessenger|JioMessages"},
        {"truecaller|caller id|dialer", "Truecaller", "BharatCaller", "Naam|JioCall"},
        {"mx player|vlc|video player", "MX Player", "Next Player", "", "MX Player ab Amazon ka hai."},
        {"google pay|gpay|paypal|venmo|wallet", "Google Pay / Wallet", "BHIM (NPCI)", "Paytm|CRED", "PhonePe ab Walmart ka hai."},
        {"gmail|outlook|yahoo mail|email", "Gmail", "Zoho Mail", "Rediffmail|Titan Mail|DataMail|NICEmail / Sandes"},
        {"chrome|goog|browser|edge|firefox|opera", "Chrome / Google", "Ulaa Browser", "JioSphere|Epic Browser|Qmamu|Aekant"},
        {"google maps|maps|navigation|waze", "Google Maps", "Mappls (MapmyIndia)", "Ola Maps"},
        {"instagram|insta|facebook|fb|tiktok|snapchat|reels|threads", "Instagram / Facebook", "ShareChat", "Moj|Josh|Chingari|Roposo"},
        {"youtube|yt", "YouTube", "AllVideo", "ApnaTube|Rangila Tube|The World Premium|JioCinema|Stage"},
        {"netflix|prime video|disney|hotstar|ott", "Netflix / Prime Video", "JioCinema", "ZEE5|Aha|hoichoi|Stage"},
        {"spotify|apple music|soundcloud", "Spotify", "JioSaavn", "KukuFM|Gaana", "Wynk Music aur Hungama Music band ho chuke hain."},
        {"amazon|ebay|aliexpress|temu|shein", "Amazon", "Flipkart", "Myntra|Meesho|JioMart|IndiaMART"},
        {"ubereats|doordash|grubhub|foodpanda|deliveroo", "Uber Eats / DoorDash", "Swiggy", "Zomato|magicpin"},
        {"uber|lyft|cab|taxi", "Uber", "Ola", "Rapido|Namma Yatri|RedBus|MakeMyTrip"},
        {"google news|flipboard|news app", "Google News", "Dailyhunt", "Inshorts|Public App"},
        {"shareit|dropbox|onedrive|wetransfer|file share", "SHAREit / Dropbox", "ShareALL", "DigiBox|JioCloud"},
        {"camscanner|document scanner|scanner", "CamScanner", "Kaagaz", ""},
        {"microsoft office|ms office|google docs|excel|powerpoint|word|notion", "MS Office / Google Docs", "Zoho Writer", "Zoho Sheet|Zoho Show|Zoho WorkDrive|Zoho Notebook"},
        {"zoom|google meet|webex|teams|skype", "Zoom / Google Meet", "JioMeet", "Zoho Meeting"},
        {"play store|app store|google play", "Play Store", "Indus Appstore", "Gov.in Appstore"},
        {"chatgpt|gemini|copilot|claude|bard", "ChatGPT", "Krutrim", "Puch AI"},
        {"pubg|call of duty|free fire|fortnite|clash of clans|bgmi", "PUBG / Call of Duty", "FAU-G: Domination", "Loco|Rooter"},
        {"myfitnesspal|strava|fitbit|fitness", "MyFitnessPal", "HealthifyMe", "August AI", "August AI doctor ka replacement nahi hai."},
        {"google search|bing|duckduckgo|search engine", "Google Search", "Qmamu", "Ulaa Browser", "Filhaal koi Indian search engine Google jaisi quality nahi deta."},
        {"vpn|nordvpn|expressvpn|surfshark", "NordVPN / ExpressVPN", "JioSphere VPN", "Aekant No-log VPN", "Anjaan 'Indian VPN' apps install na karein."},
        {"slack|discord|trello|asana", "Slack / Discord", "Zoho Cliq", "Zoho Connect"},
        {"twitter|tweet|x.com", "Twitter / X", "ShareChat", "Moj", "Koo band ho chuka hai."}
    };

    /** Reads the installed apps and reports which of them have a Made-in-India alternative. */
    private void showPhoneCheck() {
        toast("Checking the apps on this phone\u2026");
        final android.content.pm.PackageManager pm = getPackageManager();
        final List<android.content.pm.ApplicationInfo> all = installedApps();
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.LinkedHashMap<String, String[]> seen = new java.util.LinkedHashMap<>();
                int scanned = 0;
                for (android.content.pm.ApplicationInfo ai : all) {
                    String label = "";
                    try { label = String.valueOf(ai.loadLabel(pm)); } catch (Throwable e) { }
                    if (label.trim().isEmpty()) continue;
                    scanned++;
                    String[] alt = findAlt(label);
                    if (alt == null || alt.length < 3) continue;
                    if (seen.containsKey(alt[1])) continue;
                    seen.put(alt[1], new String[]{
                            label, alt[1], alt[2],
                            alt.length > 3 ? alt[3] : "",
                            alt.length > 4 ? alt[4] : ""});
                }
                final List<String[]> hits = new ArrayList<>(seen.values());
                final int count = scanned;
                runOnUiThread(new Runnable() {
                    @Override public void run() { showPhoneCheckResult(hits, count); }
                });
            }
        }).start();
    }

    private void showPhoneCheckResult(List<String[]> hits, int scanned) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(14));
        c.setBackground(bg(SURFACE, 18, BORDER, 1));
        c.addView(bold(tv("Indian alternatives for your phone", 17, TEXT)));
        String summary = scanned + " apps on this device were checked. "
                + (hits.isEmpty()
                    ? "None matched our alternatives list yet."
                    : hits.size() + (hits.size() == 1 ? " has" : " have")
                      + " a Made-in-India alternative.")
                + " Matching is by app name, so treat it as a hint.";
        TextView st = tv(summary, 13, MUTED);
        st.setPadding(0, dp(8), 0, dp(6));
        c.addView(st);
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (String[] h : hits) list.addView(phoneCheckRow(d, h));
        sv.addView(list);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView cl = bold(tv("Close", 14, TEXT));
        cl.setGravity(Gravity.CENTER);
        cl.setPadding(dp(14), dp(13), dp(14), dp(13));
        cl.setContentDescription("Close");
        cl.setBackground(bg(SURFACE2, 12, BORDER, 1));
        cl.setOnClickListener(v -> d.dismiss());
        c.addView(cl);
        d.setContentView(c);
        d.show();
    }

    private View phoneCheckRow(final Dialog d, final String[] h) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(bg(SURFACE2, 12, 0, 0));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        r.setLayoutParams(rlp);
        r.addView(bold(tv("Looks like " + h[1], 14, TEXT)));
        StringBuilder sb = new StringBuilder("Try " + h[2]);
        if (!h[3].isEmpty()) {
            for (String o : h[3].split("\\|")) if (!o.trim().isEmpty()) sb.append(", ").append(o.trim());
        }
        TextView alt = tv(sb.toString(), 13, GREEN2);
        alt.setPadding(0, dp(4), 0, 0);
        r.addView(alt);
        if (!h[4].isEmpty()) r.addView(tv(h[4], 12, MUTED));
        r.setContentDescription("Looks like " + h[1] + ". " + sb.toString());
        r.setOnClickListener(v -> {
            d.dismiss();
            AppItem a = appByName(h[2]);
            if (a != null) openApp(a);
            else openUrl("https://play.google.com/store/search?q=" + Uri.encode(h[2]) + "&c=apps");
        });
        return r;
    }

    // ---------------- user added apps ----------------

    private List<AppItem> parseApps(org.json.JSONArray arr) {
        List<AppItem> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            AppItem a = new AppItem();
            a.id = o.optString("id");
            a.name = o.optString("name");
            a.logo = o.optString("logo");
            a.pkg = o.optString("package");
            a.developer = o.optString("developer");
            a.category = o.optString("category");
            a.subcategory = o.optString("subcategory");
            a.description = o.optString("description");
            a.color = o.optString("color", "#1565C0");
            a.playUrl = o.optString("playUrl");
            a.indusUrl = o.optString("indusUrl");
            a.govtUrl = o.optString("govtUrl");
            a.apkUrl = o.optString("apkUrl");
            a.githubUrl = o.optString("githubUrl");
            a.websiteUrl = o.optString("websiteUrl");
            org.json.JSONArray tg = o.optJSONArray("tags");
            if (tg != null) for (int j = 0; j < tg.length(); j++) a.tags.add(tg.optString(j));
            if (a.id.isEmpty() || a.name.isEmpty()) continue;
            out.add(a);
        }
        return out;
    }

    private org.json.JSONArray appsToJson(List<AppItem> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (AppItem a : list) {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("id", a.id);
                o.put("name", a.name);
                o.put("package", a.pkg);
                o.put("developer", a.developer);
                o.put("category", a.category);
                o.put("subcategory", a.subcategory);
                o.put("description", a.description);
                o.put("color", a.color);
                o.put("playUrl", a.playUrl);
                o.put("indusUrl", a.indusUrl);
                o.put("websiteUrl", a.websiteUrl);
                o.put("tags", new org.json.JSONArray(a.tags));
                arr.put(o);
            } catch (Throwable ignored) { }
        }
        return arr;
    }

    /** Merge a list into the catalogue, skipping anything already there. Returns how many were new. */
    private int mergeApps(List<AppItem> add) {
        java.util.Set<String> have = new java.util.HashSet<>();
        for (AppItem a : repo.apps) have.add(a.id);
        int added = 0;
        for (AppItem a : add) {
            if (have.contains(a.id)) continue;
            repo.apps.add(a);
            have.add(a.id);
            added++;
        }
        return added;
    }

    private void loadLocalAdditions() {
        try {
            String raw = prefs.getString("local_additions", "");
            if (raw == null || raw.isEmpty()) return;
            List<AppItem> list = parseApps(new org.json.JSONArray(raw));
            mergeApps(list);
            for (AppItem a : list) localIds.add(a.id);
        } catch (Throwable ignored) { }
    }

    private void saveLocalAdditions() {
        List<AppItem> mine = new ArrayList<>();
        for (AppItem a : repo.apps) if (localIds.contains(a.id)) mine.add(a);
        prefs.edit().putString("local_additions", appsToJson(mine).toString()).apply();
    }

    private void removeLocalApp(AppItem a) {
        repo.apps.remove(a);
        localIds.remove(a.id);
        saveLocalAdditions();
        render();
        toast("Removed " + a.name);
    }

    private java.io.File additionsCache() {
        return new java.io.File(getFilesDir(), ADDITIONS_FILE);
    }

    private void loadCachedAdditions() {
        try {
            java.io.File f = additionsCache();
            if (!f.exists()) return;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            org.json.JSONObject root = new org.json.JSONObject(new String(bos.toByteArray(), "UTF-8"));
            mergeApps(parseApps(root.optJSONArray("apps")));
        } catch (Throwable ignored) { }
    }

    // pull the shared additions file so new apps reach people without a new build
    private void fetchRemoteAdditions() {
        if (REMOTE_ADDITIONS_URL == null || REMOTE_ADDITIONS_URL.isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                java.net.HttpURLConnection c = null;
                try {
                    java.net.URL u = new java.net.URL(REMOTE_ADDITIONS_URL);
                    c = (java.net.HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Accept", "application/json");
                    if (c.getResponseCode() != 200) return;
                    java.io.InputStream is = c.getInputStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
                    is.close();
                    final byte[] bytes = bos.toByteArray();
                    final org.json.JSONObject root =
                            new org.json.JSONObject(new String(bytes, "UTF-8"));
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            int added = mergeApps(parseApps(root.optJSONArray("apps")));
                            try {
                                java.io.FileOutputStream fo = new java.io.FileOutputStream(additionsCache());
                                fo.write(bytes);
                                fo.close();
                            } catch (Throwable ignored) { }
                            if (added > 0) {
                                render();
                                toast(added + (added == 1 ? " new Indian app added" : " new Indian apps added"));
                            }
                        }
                    });
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }).start();
    }

    private String slug(String x) {
        String k = x == null ? "" : x.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        while (k.startsWith("-")) k = k.substring(1);
        while (k.endsWith("-")) k = k.substring(0, k.length() - 1);
        return k.isEmpty() ? "app" : k;
    }

    private String packageFromLink(String link) {
        if (link == null) return "";
        try {
            int i = link.indexOf("id=");
            if (i >= 0) {
                String rest = link.substring(i + 3);
                int amp = rest.indexOf('&');
                if (amp >= 0) rest = rest.substring(0, amp);
                return rest.trim();
            }
        } catch (Throwable ignored) { }
        return "";
    }

    private String categoryOrOther(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (t.isEmpty()) return "Other Indian Apps";
        for (String c : repo.categories) if (c.equalsIgnoreCase(t)) return c;
        for (String c : repo.categories) if (c.toLowerCase().contains(t.toLowerCase())) return c;
        return "Other Indian Apps";
    }

    // anyone can add an Indian app. It shows on their phone right away, and gets
    // emailed on so it can be folded into the shared list.
    private void showAddAppForm() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(14));
        c.setBackground(bg(SURFACE, 18, BORDER, 1));
        c.addView(bold(tv("Add an Indian app", 17, TEXT)));
        TextView help = tv("Add any Indian app, from the Play Store, the Indus Appstore or the developer's "
                + "own site. Paste the store link and we will read the package name from it. "
                + "It appears in your app straight away, and it is sent on so it can be added for everyone.",
                12.5f, MUTED);
        help.setPadding(0, dp(8), 0, dp(6));
        c.addView(help);
        ScrollView sv = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final EditText fName = field("App name", false);
        final EditText fLink = field("Store link (Play Store, Indus or the developer's site)", false);
        final EditText fCat = field("Category (leave blank for Other Indian Apps)", false);
        final EditText fDev = field("Developer or company (optional)", false);
        final EditText fNote = field("Why is it Indian, or anything else (optional)", true);
        form.addView(fName);
        form.addView(fLink);
        form.addView(fCat);
        form.addView(fDev);
        form.addView(fNote);
        sv.addView(form);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView save = bold(tv("Add it", 14, 0xFFFFFFFF));
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(14), dp(13), dp(14), dp(13));
        save.setContentDescription("Add it");
        save.setBackground(bg(GREEN, 12, 0, 0));
        save.setOnClickListener(v -> {
            String name = fName.getText().toString().trim();
            if (name.isEmpty()) { toast("Please add the app name"); return; }
            String link = fLink.getText().toString().trim();
            AppItem a = new AppItem();
            String pkg = packageFromLink(link);
            a.pkg = pkg;
            a.id = pkg.isEmpty() ? slug(name) : slug(pkg);
            for (AppItem ex : repo.apps) {
                if (ex.id.equals(a.id)) { toast("That app is already in the list"); d.dismiss(); return; }
            }
            a.name = name;
            a.category = categoryOrOther(fCat.getText().toString());
            a.subcategory = "";
            a.developer = fDev.getText().toString().trim();
            a.description = fNote.getText().toString().trim();
            a.color = "#1B5E20";
            if (!link.isEmpty()) {
                if (link.contains("indus")) a.indusUrl = link;
                else if (link.contains("play.google")) a.playUrl = link;
                else a.websiteUrl = link;
            }
            a.tags.add(a.category);
            repo.apps.add(a);
            localIds.add(a.id);
            saveLocalAdditions();
            d.dismiss();
            render();
            toast("Added " + name);
            String body = "New Indian app for Swadeshi Appstore\n\n"
                    + "Name: " + a.name + "\n"
                    + "Package: " + (a.pkg.isEmpty() ? "(not detected)" : a.pkg) + "\n"
                    + "Category: " + a.category + "\n"
                    + "Developer: " + a.developer + "\n"
                    + "Link: " + link + "\n"
                    + "Notes: " + a.description + "\n";
            sendSubmit(body);
        });
        c.addView(save);
        d.setContentView(c);
        d.show();
    }

    private String[] findAlt(String q) {
        if (q == null) return null;
        q = q.trim().toLowerCase();
        if (q.isEmpty()) return null;
        String[] best = null; int bestScore = 0;
        for (String[] e : ALTS) {
            for (String k : e[0].split("\\|")) {
                if (k.isEmpty()) continue;
                if (!(q.equals(k) || q.contains(k) || k.startsWith(q))) continue;
                int score = (q.equals(k) ? 1000 : 0) + k.length();
                if (score > bestScore) { bestScore = score; best = e; }
            }
        }
        return best;
    }
    private AppItem appByName(String name) {
        String k = name.trim().toLowerCase();
        for (AppItem a : repo.apps) if (a.name.toLowerCase().equals(k)) return a;
        for (AppItem a : repo.apps) if (a.name.toLowerCase().startsWith(k)) return a;
        return null;
    }
    private View altBanner(String[] e) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF10301D, SURFACE});
        g.setCornerRadius(dp(16));
        g.setStroke(Math.max(1, dp(1)), 0xFF2E7D4F);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(14), dp(12), dp(14), 0);
        b.setLayoutParams(lp);
        b.addView(bold(tv("\uD83C\uDDEE\uD83C\uDDF3  TRY A MADE IN INDIA ALTERNATIVE", 12, 0xFF6DDCB0)));
        List<String> names = new ArrayList<>();
        names.add(e[2]);
        if (!e[3].isEmpty()) for (String x : e[3].split("\\|")) names.add(x);
        StringBuilder sb = new StringBuilder("You searched for ");
        sb.append(e[1]).append(" \u2014 try ").append(names.get(0));
        for (int i = 1; i < Math.min(3, names.size()); i++) sb.append(i == 1 ? " & " : ", ").append(names.get(i));
        if (names.size() > 3) sb.append(", ").append(names.get(3));
        sb.append(".");
        TextView m = tv(sb.toString(), 14, TEXT);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(6);
        b.addView(m, mlp);
        if (e.length > 4 && !e[4].isEmpty()) {
            TextView nt = tv(e[4], 12, 0xFF9AA0A6);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(6);
            b.addView(nt, nlp);
        }
        return b;
    }
    private View altRow(String name) {
        AppItem a = appByName(name);
        if (a != null) return row(a);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView mono = bold(tv(name.substring(0, 1).toUpperCase(), 18, 0xFFFFFFFF));
        mono.setGravity(Gravity.CENTER);
        mono.setBackground(bg(0xFFE42527, 12, 0, 0));
        r.addView(mono, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(bold(tv(name, 14.5f, TEXT)));
        col.addView(tv("Made-in-India alternative", 12, MUTED));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(12);
        r.addView(col, clp);
        TextView sb = bold(tv("Search", 11, 0xFFFFFFFF));
        sb.setPadding(dp(12), dp(8), dp(12), dp(8));
        sb.setBackground(bg(GREEN, 10, GREEN, 1));
        final String nm = name;
        sb.setOnClickListener(v -> openUrl("https://play.google.com/store/search?q=" + Uri.encode(nm) + "&c=apps"));
        r.addView(sb);
        return r;
    }
    private void renderAlternatives() {
        view.addView(section("Swadeshi alternatives"));
        TextView note = tv("Global apps and their Made-in-India replacements. Tap one to see the alternatives.", 13, MUTED);
        note.setPadding(dp(16), 0, dp(16), dp(10));
        view.addView(note);
        for (String[] e : ALTS) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(16), dp(12), dp(16), dp(12));
            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_alternatives);
            ic.setColorFilter(MUTED);
            LinearLayout ib = new LinearLayout(this);
            ib.setGravity(Gravity.CENTER);
            ib.setBackground(bg(SURFACE2, 12, 0, 0));
            ib.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
            r.addView(ib, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(bold(tv(e[1], 14.5f, TEXT)));
            String others = e[3].isEmpty() ? "" : " \u00b7 " + e[3].replace("|", " \u00b7 ");
            col.addView(tv(e[2] + others, 12, MUTED));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = dp(12);
            r.addView(col, clp);
            r.addView(tv("\u203A", 20, MUTED));
            final String foreign = e[1];
            r.setOnClickListener(v -> { tab = "search"; chip = "top"; category = null; query = foreign; render(); });
            view.addView(r);
        }
    }

    private void crashLog(Throwable e) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir != null) {
                java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "last_error.txt"));
                w.write(android.util.Log.getStackTraceString(e));
                w.close();
            }
        } catch (Throwable ignored) { }
    }

    private static final String COPYRIGHT =
            "\u00a9 2026 Vasudev Dangaya. All rights reserved.";

    private static final String LEGAL_PRIVACY =
        "Privacy Policy\n\nLast updated 15 October 2026\n\n"
      + "Swadeshi Appstore is a free, offline directory of Indian apps. This policy explains, in plain words, "
      + "what the app stores and what it does not.\n\n"
      + "What we store\nWe ask for a name and, optionally, an email, and we store the apps you bookmark. Nothing else. "
      + "No phone number, no location, no contacts, no files, no device identifiers. If you leave the email blank "
      + "the app works exactly the same.\n\n"
      + "Where it is stored\nYour name, email and bookmarks stay in this app's private storage on your device. "
      + "They are never uploaded, and there is no account and no server that stores your data. The app downloads a "
      + "public list of apps so new listings reach you without an update; that request carries no personal information.\n\n"
      + "Legal basis and consent (DPDP Act, 2023)\nWe process your name and optional email only because you choose "
      + "to type them, which is your consent under India's Digital Personal Data Protection Act, 2023. You can "
      + "withdraw that consent at any time by signing out, which erases them from this device.\n\n"
      + "Retention\nYour name and email stay on the device until you sign out or uninstall the app. Bookmarks stay "
      + "until you clear them or uninstall. We keep nothing on any server, so there is nothing for us to retain.\n\n"
      + "Security\nYour details sit in the app's private storage, which other apps cannot read, and nothing leaves "
      + "the device, so there is no transmission to intercept. No app can fully protect data from a device owner "
      + "with full control of the phone, so please keep your device locked.\n\n"
      + "Cookies and analytics\nThe app sets no cookies and runs no analytics, advertising or tracking code. "
      + "It contains no third-party SDKs.\n\n"
      + "Children\nThis app is not directed at children under 13, and we do not knowingly store a child's data. "
      + "If a child has used it, sign out to erase the details.\n\n"
      + "Your rights and deleting your data\nYou can see, change or erase your details at any time: Sign out removes "
      + "your name and email, Clear saved apps removes your bookmarks, and uninstalling removes everything. "
      + "To ask a question or raise a concern, contact our Grievance Officer below.\n\n"
      + "Grievance Officer\nName: Vasudev Dangaya. Email: " + DEV_EMAIL + ". We acknowledge every message and reply within 30 days.\n\n"
      + "Changes\nIf this policy changes we will update the date above and ship the new text with an app update.\n\n"
      + "Operator\nVasudev Dangaya, independent developer, India. Contact: " + DEV_EMAIL
      + "\n\n" + COPYRIGHT;

    private static final String LEGAL_TERMS =
        "Terms and Conditions\n\nLast updated 15 October 2026\n\n"
      + "1. What this app is\nA free directory that helps you find Indian apps and open their official store or website pages. "
      + "We are not affiliated with, and do not endorse, the apps listed.\n\n"
      + "2. Price\nThis app is free. No paid tier, no subscription, no hidden fee. "
      + "No listing is ranked because someone paid.\n\n"
      + "3. Reviews and claims\nWe publish no user reviews, no star ratings and no download counts, because we cannot verify them.\n\n"
      + "4. Links and downloads\nStore links and APK links open the official publisher. "
      + "Anything you download there is between you and that publisher.\n\n"
      + "5. Content and trademarks\nApp icons and names belong to their owners and are shown only to identify the app. "
      + "If you own a right in a listing and want it changed or removed, email " + DEV_EMAIL + " with the app name "
      + "and we will act on a valid request, normally within 7 days.\n\n"
      + "6. Your submissions\nIf you add or submit an app you confirm the details are accurate and that you have the "
      + "right to share them. We may edit or remove any listing at any time.\n\n"
      + "7. No warranty\nThis app is provided \"as is\" and \"as available\". We do not promise it will be "
      + "uninterrupted or error free, or that every listing is accurate or still available. App information can "
      + "change without notice.\n\n"
      + "8. Limitation of liability\nTo the fullest extent allowed by law, we are not liable for any indirect or "
      + "consequential loss, or for any loss arising from an app you find here, a link you open, or a download you "
      + "make. Nothing in these terms limits liability that cannot be limited by law.\n\n"
      + "9. Age\nThis app is meant for users aged 13 and above. If you are under 13, please do not use it.\n\n"
      + "10. Termination\nYou may stop using the app at any time by uninstalling it. We may suspend or withdraw the "
      + "app, or any listing, at any time.\n\n"
      + "11. Changes to these terms\nWe may update these terms. The date above shows the latest version, and continued "
      + "use after an update means you accept it.\n\n"
      + "12. Governing law and jurisdiction\nThese terms are governed by the laws of India, and the courts of India "
      + "have exclusive jurisdiction over any dispute.\n\n"
      + "13. Contact\n" + DEV_EMAIL
      + "\n\n" + COPYRIGHT;

    private static final String LEGAL_REFUND =
        "Refund Policy\n\nLast updated 15 October 2026\n\n"
      + "Swadeshi Appstore is free and we sell nothing through it, so there is no purchase to refund.\n\n"
      + "Optional tips\nThe Support section lets you send an optional tip over UPI. It is a voluntary gift, not a payment "
      + "for a product, and it buys no feature or position. Because it is a gift it is not refundable. If you sent one "
      + "by mistake, email " + DEV_EMAIL + " within 7 days and we will return it.\n\n"
      + "Purchases made elsewhere\nSome listings link to the Play Store, Indus Appstore or a developer's own site. "
      + "Those purchases follow that seller's refund policy. We cannot refund them.\n\n"
      + "Contact\n" + DEV_EMAIL
      + "\n\n" + COPYRIGHT;

    private static final String LEGAL_COOKIES =
        "Cookie Policy\n\nLast updated 15 October 2026\n\n"
      + "Short version: this app sets no cookies.\n\n"
      + "Cookies\nWe set no first-party or third-party cookies, and we run no advertising or analytics cookies. "
      + "The app has no tracking pixels and no advertising identifiers.\n\n"
      + "What we store instead\nInstead of cookies we keep a small amount of data in the app's own private storage on "
      + "your device: your name, an optional email, and your bookmarks. This is used only to run the app on your "
      + "phone and is never sent anywhere.\n\n"
      + "Why there are no cookies\nCookies are a browser technology for recognising you across visits. This app is an "
      + "offline directory with no login and no server, so it has no need for them.\n\n"
      + "Your control\nSign out clears your name and email. Clear saved apps clears your bookmarks. "
      + "Uninstalling the app clears both.\n\n"
      + "Third-party sites\nWebsites you open from a listing (a store page or a developer's site) set their own "
      + "cookies under their own policies. We do not control those, so please read their policies.\n\n"
      + "Contact\n" + DEV_EMAIL
      + "\n\n" + COPYRIGHT;

    private static final String LEGAL_ACCESSIBILITY =
        "Accessibility Statement\n\nLast updated 15 October 2026\n\n"
      + "We want Swadeshi Appstore to be usable by as many people as possible. Here is what the app "
      + "does today, stated plainly.\n\n"
      + "What we provide\nReadable text that follows your device font size. Light, dark and system themes, "
      + "and a choice of accent colours. Controls are sized for comfortable tapping. Buttons and icons "
      + "carry labels so screen readers can announce them. The app works fully offline, so a poor "
      + "connection does not block it.\n\n"
      + "What we do not claim\nWe have not carried out a formal accessibility audit, so we do not claim "
      + "conformance with WCAG or any other standard. We will not state a level we cannot prove.\n\n"
      + "Telling us about a problem\nIf something is hard to use, email " + DEV_EMAIL + " and describe it. "
      + "We will do our best to fix it.\n\n"
      + "Operator\nVasudev Dangaya, independent developer, India. Contact: " + DEV_EMAIL
      + "\n\n" + COPYRIGHT;

    private static final String LEGAL_SECURITY =
        "Security Policy\n\nLast updated 15 October 2026\n\n"
      + "Swadeshi Appstore is built so that there is very little to attack.\n\n"
      + "How the app is built\nIt has no server, no account and no login. It stores your name, an optional "
      + "email and your bookmarks in the app's own private storage on your device. It runs no analytics, "
      + "no advertising and no third-party code, so there is no third-party SDK that could leak your data.\n\n"
      + "Network\nAll traffic uses HTTPS. Cleartext traffic is switched off and only system certificate "
      + "authorities are trusted, which makes interception harder.\n\n"
      + "Updates\nSecurity fixes ship as normal app updates from the store.\n\n"
      + "What we do not claim\nWe hold no security certification and make no such claim.\n\n"
      + "Reporting a vulnerability\nSee Responsible Disclosure, or email " + DEV_EMAIL + ".\n\n"
      + "Operator\nVasudev Dangaya, independent developer, India. Contact: " + DEV_EMAIL
      + "\n\n" + COPYRIGHT;

    private static final String LEGAL_DISCLOSURE =
        "Responsible Disclosure\n\nLast updated 15 October 2026\n\n"
      + "If you find a security problem in Swadeshi Appstore, please tell us privately first.\n\n"
      + "How to report\nEmail " + DEV_EMAIL + " with a short description, the steps to reproduce it and the "
      + "app version. Please do not open a public issue before we have had a chance to fix it.\n\n"
      + "What to expect\nWe will acknowledge your message and reply within 30 days. If the report is valid "
      + "we will fix it in an app update and credit you if you wish.\n\n"
      + "Please do not\nDo not access, change or delete other people's data, do not run denial-of-service "
      + "tests, and do not test on a device you do not own.\n\n"
      + "Operator\nVasudev Dangaya, independent developer, India. Contact: " + DEV_EMAIL
      + "\n\n" + COPYRIGHT;

    /** A distinct icon for each legal document. */
    private int legalIcon(String key) {
        switch (key) {
            case "privacy": return R.drawable.ic_privacy;
            case "terms": return R.drawable.ic_terms;
            case "refund": return R.drawable.ic_receipt;
            case "cookie": return R.drawable.ic_cookie;
            case "accessibility": return R.drawable.ic_accessibility;
            case "security": return R.drawable.ic_verified_user;
            case "disclosure": return R.drawable.ic_bug;
            default: return R.drawable.ic_shield;
        }
    }

    private void showLegal(String title, String body) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(12));
        c.setBackground(bg(SURFACE, 18, BORDER, 1));
        c.addView(bold(tv(title, 17, TEXT)));
        ScrollView sv = new ScrollView(this);
        TextView t = tv(body, 13, MUTED);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(10), 0, dp(10));
        sv.addView(t);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView ok = bold(tv("Close", 14, 0xFFFFFFFF));
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(dp(14), dp(13), dp(14), dp(13));
        ok.setContentDescription("Close");
        ok.setBackground(bg(GREEN, 12, 0, 0));
        ok.setOnClickListener(v -> d.dismiss());
        c.addView(ok);
        d.setContentView(c);
        d.show();
    }

    /** Title, scrollable body, up to four action buttons. */
    private void showSheet(String title, String body, String[][] actions) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(14));
        c.setBackground(bg(SURFACE, 18, BORDER, 1));
        c.addView(bold(tv(title, 17, TEXT)));
        ScrollView sv = new ScrollView(this);
        TextView t = tv(body, 13, MUTED);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(10), 0, dp(10));
        sv.addView(t);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        for (String[] a : actions) {
            TextView b = bold(tv(a[0], 14, 0xFFFFFFFF));
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(14), dp(13), dp(14), dp(13));
            b.setContentDescription(a[0]);
            b.setBackground(bg(GREEN, 12, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            b.setLayoutParams(lp);
            final String url = a[1];
            if ("#close".equals(url)) b.setOnClickListener(v -> d.dismiss());
            else if ("#submit".equals(url)) b.setOnClickListener(v -> { d.dismiss(); showAddAppForm(); });
            else if ("#add".equals(url)) b.setOnClickListener(v -> { d.dismiss(); showAddAppForm(); });
            else if ("#support".equals(url)) b.setOnClickListener(v -> { d.dismiss(); showSupport(); });
            else if ("#tip".equals(url)) b.setOnClickListener(v -> { d.dismiss(); showTipSheet(); });
            else if ("#pp".equals(url)) b.setOnClickListener(v -> { d.dismiss(); openPlayProtect(); });
            else if ("#settings".equals(url)) b.setOnClickListener(v -> {
                d.dismiss();
                try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS)); }
                catch (Throwable e) { toast("Settings unavailable"); }
            });
            else b.setOnClickListener(v -> { d.dismiss(); openUrl(url); });
            c.addView(b);
        }
        d.setContentView(c);
        d.show();
    }

    // Play Protect is Google's; a third-party app can't switch it on. So we open the
    // real Play Protect screen and run a quick local check of our own alongside it.
    private void showSafetyScan() {
        int withSensitive = 0, sideloaded = 0, checked = 0;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                checked++;
                try {
                    String installer = null;
                    try { installer = pm.getInstallerPackageName(ai.packageName); } catch (Throwable e) {}
                    if (installer == null || installer.isEmpty()) sideloaded++;
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(ai.packageName,
                            android.content.pm.PackageManager.GET_PERMISSIONS);
                    if (pi.requestedPermissions != null) {
                        for (String p : pi.requestedPermissions) {
                            if ("android.permission.SEND_SMS".equals(p)
                                    || "android.permission.READ_SMS".equals(p)
                                    || "android.permission.RECEIVE_SMS".equals(p)
                                    || "android.permission.CALL_PHONE".equals(p)
                                    || "android.permission.SYSTEM_ALERT_WINDOW".equals(p)
                                    || "android.permission.REQUEST_INSTALL_PACKAGES".equals(p)
                                    || "android.permission.ACCESS_FINE_LOCATION".equals(p)) {
                                withSensitive++;
                                break;
                            }
                        }
                    }
                } catch (Throwable e) {}
            }
        } catch (Throwable e) {}
        String body = "Play Protect is Google's own scanner and only Google can turn it on. "
                + "The button below opens the real Play Protect screen, where you can switch it on or run a scan.\n\n"
                + "Meanwhile, here is a quick local check of this device.\n\n"
                + "Apps checked: " + checked + "\n"
                + "Sideloaded (not installed from a store): " + sideloaded + "\n"
                + "Apps asking for a sensitive permission: " + withSensitive + "\n\n"
                + "A sensitive permission is one like SMS, phone calls, location or install-other-apps. "
                + "Many legitimate apps ask for these, so treat this as a prompt to look, not a verdict. "
                + "This app has no malware database and makes no claim to be an antivirus.";
        showSheet("Play Protect and device safety", body,
                new String[][]{{"Open Play Protect settings", "#pp"}, {"Close", "#close"}});
    }

    private void openPlayProtect() {
        String[][] targets = {
            {"com.google.android.gms", "com.google.android.gms.security.settings.VerifyAppsSettingsActivity"},
            {"com.google.android.gms", "com.google.android.gms.security.settings.SecuritySettingsActivity"},
            {"com.android.vending", "com.google.android.finsky.settings.SettingsActivity"}
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setClassName(t[0], t[1]);
                startActivity(i);
                return;
            } catch (Throwable ignored) { }
        }
        try { startActivity(new Intent("android.settings.SECURITY_SETTINGS")); return; } catch (Throwable ignored) { }
        toast("Play Protect settings are not available on this device");
    }

    // no server, so you move the saved list yourself: copy, share or save a file
    private void showSync() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Swadeshi Appstore - my saved apps (").append(saved.size()).append(")\n\n");
        for (AppItem a : repo.apps) {
            if (saved.contains(a.id)) {
                sb.append("- ").append(a.name);
                if (notEmpty(a.pkg)) sb.append("  (").append(a.pkg).append(")");
                sb.append("\n");
            }
        }
        if (saved.isEmpty()) sb.append("(nothing saved yet)\n");
        sb.append("\nExported from Swadeshi Appstore on this device.");
        final String text = sb.toString();
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(14));
        c.setBackground(bg(SURFACE, 18, BORDER, 1));
        c.addView(bold(tv("Sync apps to devices", 17, TEXT)));
        TextView t = tv("This app has no server, so it cannot sync by itself. "
                + "To move your saved list to another device, send it across yourself.\n\n"
                + "Your list right now:\n\n" + text, 13, MUTED);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(10), 0, dp(10));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        String[] acts = {"Copy list", "Share list", "Save as a text file", "Close"};
        for (String label : acts) {
            TextView b = bold(tv(label, 14, 0xFFFFFFFF));
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(14), dp(13), dp(14), dp(13));
            b.setContentDescription(label);
            b.setBackground(bg(GREEN, 12, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                d.dismiss();
                if ("Copy list".equals(label)) {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Saved apps", text));
                        toast("Saved list copied");
                    } catch (Throwable e) { toast("Could not copy"); }
                } else if ("Share list".equals(label)) {
                    try {
                        Intent shr = new Intent(Intent.ACTION_SEND);
                        shr.setType("text/plain");
                        shr.putExtra(Intent.EXTRA_TEXT, text);
                        startActivity(Intent.createChooser(shr, "Share saved apps"));
                    } catch (Throwable e) { toast("Nothing to share with"); }
                } else if ("Save as a text file".equals(label)) {
                    saveTextToFile(text);
                }
            });
            c.addView(b);
        }
        d.setContentView(c);
        d.show();
    }

    private void saveTextToFile(String text) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) { toast("Storage not available"); return; }
            java.io.File f = new java.io.File(dir, "swadeshi-saved-apps.txt");
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(text);
            w.close();
            toast("Saved to " + f.getAbsolutePath());
        } catch (Throwable e) { toast("Could not save the file"); }
    }

    // email, GitHub and the optional UPI tip, all in one place
    private void showSupport() {
        showSheet("Support & contact",
                "One place for everything. The app has no server and no account, so nothing here is automated.\n\n"
                + "Email: " + DEV_EMAIL + "\n"
                + "GitHub: github.com/vasudev-work\n\n"
                + "The app is free and stays free. If it helped you, an optional UPI tip is welcome but never expected, "
                + "and it buys no feature or position.",
                new String[][]{{"Email the developer", "mailto:" + DEV_EMAIL},
                        {"Open GitHub profile", GITHUB_PROFILE},
                        {"Send a tip on UPI", "#tip"},
                        {"Close", "#close"}});
    }

    /** Tip sheet: two quick amounts plus a custom amount with a minimum. No server involved. */
    private void showTipSheet() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(14));
        c.setBackground(bg(SURFACE, 18, BORDER, 1));
        c.addView(bold(tv("Support the developer", 17, TEXT)));
        TextView msg = tv("If you like this app, buy the developer a chai. Every tip keeps the updates "
                + "coming, and none of it is required.", 12.5f, MUTED);
        msg.setPadding(0, dp(8), 0, dp(12));
        c.addView(msg);

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        TextView chai = tipChip("\uD83C\uDF75 Chai \u20B950");
        TextView coffee = tipChip("\u2615 Coffee \u20B9100");
        chai.setOnClickListener(v -> { d.dismiss(); sendTip(50); });
        coffee.setOnClickListener(v -> { d.dismiss(); sendTip(100); });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(8);
        quick.addView(chai, clp);
        quick.addView(coffee, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(quick);

        final EditText amt = field("Custom amount (\u20B9), minimum " + TIP_MIN, false);
        amt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        amt.setLayoutParams(alp);
        c.addView(amt);

        TextView tip = bold(tv("Tip", 14, 0xFFFFFFFF));
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dp(14), dp(13), dp(14), dp(13));
        tip.setContentDescription("Tip");
        tip.setBackground(bg(GREEN, 12, 0, 0));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        tip.setLayoutParams(tlp);
        tip.setOnClickListener(v -> {
            String s = amt.getText().toString().trim();
            if (s.isEmpty()) { toast("Enter an amount, or pick chai or coffee"); return; }
            int n;
            try { n = Integer.parseInt(s); } catch (Throwable e) { toast("Enter a number"); return; }
            if (n < TIP_MIN) { toast("Minimum tip is \u20B9" + TIP_MIN); return; }
            d.dismiss();
            sendTip(n);
        });
        c.addView(tip);

        TextView close = bold(tv("Close", 14, TEXT));
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(14), dp(13), dp(14), dp(13));
        close.setContentDescription("Close");
        close.setBackground(bg(SURFACE2, 12, BORDER, 1));
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        xlp.topMargin = dp(8);
        close.setLayoutParams(xlp);
        close.setOnClickListener(v -> d.dismiss());
        c.addView(close);

        d.setContentView(c);
        d.show();
        if (d.getWindow() != null)
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
    }

    private TextView tipChip(String label) {
        TextView b = bold(tv(label, 14, TEXT));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        b.setContentDescription(label);
        b.setBackground(bg(SURFACE2, 12, BORDER, 1));
        return b;
    }

    /** Opens the user's UPI app with the tip payee and amount pre-filled. No server involved. */
    private void sendTip(int amount) {
        try {
            String uri = "upi://pay?pa=" + Uri.encode(UPI_ID) + "&pn=" + Uri.encode(UPI_PAYEE)
                    + "&cu=INR&am=" + amount;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (Throwable e) {
            toast("No UPI app found on this device");
        }
    }

    // replaces the old fake notification badge
    private void showUpdates() {
        int added = 0;
        for (AppItem a : repo.apps) if (localIds.contains(a.id)) added++;
        showSheet("Your activity",
                "This app has no server and sends no push notifications, so there is nothing to notify you about here.\n\n"
                + "Apps in the catalogue: " + repo.apps.size() + "\n"
                + "Apps you added: " + added + "\n"
                + "Apps you saved: " + saved.size() + "\n\n"
                + "New apps reach everyone through the shared list. Use Add an Indian app to suggest one.",
                new String[][]{{"Close", "#close"}});
    }

    private void showRatings() {
        showSheet("Ratings and reviews",
                "We do not publish star ratings or user reviews inside the app. We have no way to verify them, "
                + "and we will not show a number we cannot stand behind.\n\n"
                + "To rate the app or report a problem, use Support & contact.\n\n"
                + "Reviews for the individual Indian apps listed here live on their own store pages, not here.",
                new String[][]{{"Open Support & contact", "#support"}, {"Close", "#close"}});
    }

    private void showContributions() {
        StringBuilder sb = new StringBuilder();
        sb.append("Apps you saved: ").append(saved.size()).append("\n");
        for (AppItem a : repo.apps) if (saved.contains(a.id)) sb.append("- ").append(a.name).append("\n");
        if (saved.isEmpty()) sb.append("(none yet)\n");
        sb.append("\nYour name on this device: ").append(userName.isEmpty() ? "not set" : userName).append("\n");
        sb.append("\nWant your own app listed here? Use Add an Indian app in the You tab. "
                + "Bug reports and suggestions go to Support & contact.");
        showSheet("Your contributions", sb.toString(),
                new String[][]{{"Add an Indian app", "#add"}, {"Open Support & contact", "#support"}, {"Close", "#close"}});
    }

    private void showAbout() {
        showSheet("About Swadeshi Appstore",
                "Swadeshi Appstore is a free, offline directory of Indian apps, built to make it easy to find "
                + "Made-in-India alternatives.\n\n"
                + "Version " + appVersion() + "\n"
                + "Developed by Vasudev Dangaya\n"
                + "Catalog: " + repo.apps.size() + " apps\n"
                + "Feedback: " + DEV_EMAIL + "\n\n"
                + "It sets no cookies, runs no analytics and sends nothing off your device. "
                + "The full Privacy Policy, Terms, Refund Policy and Cookie Policy are in the Legal section of this tab.\n\n"
                + COPYRIGHT,
                new String[][]{{"Open Support & contact", "#support"}, {"Close", "#close"}});
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable e) { return "0.7"; }
    }

    private void openDebugMenu() {
        try {
            Class<?> c = Class.forName("com.vasudev.swadeshistore.DebugActivity");
            startActivity(new Intent(this, c));
        } catch (Throwable e) {
            toast("Debug menu is only in the TEST build");
        }
    }

    /** In-app submission form. Submissions reach the developer by email. */
    private void showSubmitForm() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(14));
        c.setBackground(bg(SURFACE, 18, BORDER, 1));
        c.addView(bold(tv("Submit your app", 17, TEXT)));
        TextView help = tv("Tell us about an Indian app we should list. We check every submission before it goes in. "
                + "No fee, and paying never buys a higher position.", 12.5f, MUTED);
        help.setPadding(0, dp(8), 0, dp(6));
        c.addView(help);
        ScrollView sv = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final EditText fName = field("App name", false);
        final EditText fLink = field("Play Store or website link", false);
        final EditText fDev = field("Developer or company", false);
        final EditText fCat = field("Category", false);
        final EditText fMail = field("Your email (so we can reply)", false);
        final EditText fNote = field("Anything else", true);
        form.addView(fName);
        form.addView(fLink);
        form.addView(fDev);
        form.addView(fCat);
        form.addView(fMail);
        form.addView(fNote);
        sv.addView(form);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView send = bold(tv("Send by email", 14, 0xFFFFFFFF));
        send.setGravity(Gravity.CENTER);
        send.setPadding(dp(14), dp(13), dp(14), dp(13));
        send.setContentDescription("Send by email");
        send.setBackground(bg(GREEN, 12, 0, 0));
        send.setOnClickListener(v -> {
            if (!notEmpty(fName.getText().toString())) { toast("Please add the app name"); return; }
            String body = submitBody(fName, fLink, fDev, fCat, fMail, fNote);
            d.dismiss();
            sendSubmit(body);
        });
        c.addView(send);
        TextView copy = bold(tv("Copy details instead", 14, TEXT));
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp(14), dp(13), dp(14), dp(13));
        copy.setBackground(bg(SURFACE2, 12, BORDER, 1));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        copy.setLayoutParams(clp);
        copy.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("App submission",
                        submitBody(fName, fLink, fDev, fCat, fMail, fNote)));
                toast("Copied. Email it to " + DEV_EMAIL);
            } catch (Throwable e) { toast("Could not copy"); }
            d.dismiss();
        });
        c.addView(copy);
        d.setContentView(c);
        d.show();
    }

    private String submitBody(EditText a, EditText b, EditText c, EditText e, EditText f, EditText g) {
        return "App name: " + a.getText() + "\n"
                + "Link: " + b.getText() + "\n"
                + "Developer: " + c.getText() + "\n"
                + "Category: " + e.getText() + "\n"
                + "Reply to: " + f.getText() + "\n"
                + "Notes: " + g.getText() + "\n";
    }

    private EditText field(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setBackground(bg(SURFACE2, 10, BORDER, 1));
        e.setPadding(dp(12), dp(11), dp(12), dp(11));
        if (multiline) { e.setMinLines(2); e.setMaxLines(4); } else { e.setSingleLine(true); }
        e.setInputType(multiline ? (android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                : android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private void sendSubmit(String body) {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO);
            i.setData(Uri.parse("mailto:" + DEV_EMAIL));
            i.putExtra(Intent.EXTRA_SUBJECT, "App submission for Swadeshi Appstore");
            i.putExtra(Intent.EXTRA_TEXT, body);
            startActivity(i);
        } catch (Throwable e) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("App submission", body));
                toast("No email app. Details copied, send them to " + DEV_EMAIL);
            } catch (Throwable e2) { toast("Email " + DEV_EMAIL + " to submit"); }
        }
    }

    /** Real installed-app details: which of our catalog is on this device. */
    private void showInstalledDetails() {
        List<AppItem> inst = new ArrayList<>();
        for (AppItem a : repo.apps) if (isInstalled(a)) inst.add(a);
        StringBuilder sb = new StringBuilder();
        sb.append(inst.size()).append(" of the ").append(repo.apps.size())
                .append(" apps in our catalog are installed on this device.\n\n");
        if (inst.isEmpty()) {
            sb.append("None matched yet. Matching uses the package name or the app name, so a renamed app may be missed.");
        } else {
            for (AppItem a : inst) {
                sb.append("- ").append(a.name);
                if (notEmpty(a.pkg)) sb.append("  (").append(a.pkg).append(")");
                sb.append("\n");
            }
        }
        showSheet("Installed apps", sb.toString(),
                new String[][]{{"Open device app settings", "#settings"}, {"Close", "#close"}});
    }

    private TextView section(String s) {
        TextView t = bold(tv(s, 16, TEXT));
        t.setPadding(dp(16), dp(16), dp(16), dp(10));
        return t;
    }

    private void renderApps() {
        if ("categories".equals(chip)) { renderCategories(); return; }
        if ("alternatives".equals(chip)) { renderAlternatives(); return; }
        List<AppItem> list;
        if ("govt".equals(chip)) list = byCat("Government, e-Governance & Citizen Services");
        else if ("fintech".equals(chip)) list = byCat("Fintech, Payments & Banking");
        else if ("social".equals(chip)) list = byCat("Social Media, Messaging & Networking");
        else if ("games".equals(chip)) list = byCat("Gaming & Esports");
        else if ("children".equals(chip)) { list = byCat("EdTech & Learning"); list.addAll(byCat("Gaming & Esports")); }
        else list = repo.apps;

        if (category != null) {
            TextView back = bold(tv("\u2039  All categories", 13, GREEN2));
            back.setPadding(dp(16), dp(10), dp(16), dp(10));
            back.setContentDescription("Back to all categories");
            back.setOnClickListener(v -> { category = null; chip = "categories"; showAll = false; render(); });
            view.addView(back);
            List<AppItem> cl = byCat(category);
            view.addView(section(category + "  (" + cl.size() + ")"));
            if (cl.isEmpty()) {
                TextView e = tv("No apps in this category yet.", 14, MUTED);
                e.setGravity(Gravity.CENTER);
                e.setPadding(dp(20), dp(50), dp(20), dp(50));
                view.addView(e);
            } else {
                addRows(cl);
            }
            return;
        }
        if ("govt".equals(chip) || "children".equals(chip) || "fintech".equals(chip)
                || "social".equals(chip) || "games".equals(chip)) {
            String ttl = "govt".equals(chip) ? "Government apps & citizen services"
                    : "fintech".equals(chip) ? "Fintech, payments & banking"
                    : "social".equals(chip) ? "Social, messaging & networking"
                    : "games".equals(chip) ? "Games & esports" : "For children";
            view.addView(section(ttl));
            addRows(list);
            return;
        }
        // default feed
        LinearLayout chk = new LinearLayout(this);
        chk.setOrientation(LinearLayout.HORIZONTAL);
        chk.setGravity(Gravity.CENTER_VERTICAL);
        chk.setPadding(dp(16), dp(16), dp(16), dp(16));
        chk.setBackground(bg(SURFACE, 16, GREEN2, 1));
        chk.setContentDescription("Check my phone for Indian alternatives");
        ImageView chki = new ImageView(this);
        chki.setImageResource(R.drawable.ic_search);
        chki.setColorFilter(GREEN2);
        LinearLayout chkb = new LinearLayout(this);
        chkb.setGravity(Gravity.CENTER);
        chkb.setBackground(bg(SURFACE2, 12, 0, 0));
        chkb.addView(chki, new LinearLayout.LayoutParams(dp(22), dp(22)));
        chk.addView(chkb, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout chkc = new LinearLayout(this);
        chkc.setOrientation(LinearLayout.VERTICAL);
        chkc.addView(bold(tv("Check my phone", 15, TEXT)));
        chkc.addView(tv("See which apps you already have a Made-in-India alternative for.", 12.5f, MUTED));
        LinearLayout.LayoutParams chklp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        chklp.leftMargin = dp(12);
        chk.addView(chkc, chklp);
        chk.addView(tv("\u203A", 20, MUTED));
        chk.setOnClickListener(v -> showPhoneCheck());
        LinearLayout.LayoutParams chkwrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chkwrap.setMargins(dp(14), dp(12), dp(14), 0);
        chk.setLayoutParams(chkwrap);
        view.addView(chk);

        LinearLayout addb = new LinearLayout(this);
        addb.setOrientation(LinearLayout.HORIZONTAL);
        addb.setGravity(Gravity.CENTER_VERTICAL);
        addb.setPadding(dp(16), dp(14), dp(16), dp(14));
        addb.setBackground(bg(SURFACE, 14, BORDER, 1));
        addb.setContentDescription("Add an Indian app");
        ImageView addi = new ImageView(this);
        addi.setImageResource(R.drawable.ic_apps);
        addi.setColorFilter(SAFFRON);
        LinearLayout addib = new LinearLayout(this);
        addib.setGravity(Gravity.CENTER);
        addib.setBackground(bg(SURFACE2, 12, 0, 0));
        addib.addView(addi, new LinearLayout.LayoutParams(dp(20), dp(20)));
        addb.addView(addib, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout addc = new LinearLayout(this);
        addc.setOrientation(LinearLayout.VERTICAL);
        addc.addView(bold(tv("Know an Indian app we missed?", 14.5f, TEXT)));
        addc.addView(tv("Add it. It appears on your phone now and can be shared with everyone.", 12, MUTED));
        LinearLayout.LayoutParams addlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addlp.leftMargin = dp(12);
        addb.addView(addc, addlp);
        addb.addView(tv("\u203A", 20, MUTED));
        addb.setOnClickListener(v -> showAddAppForm());
        LinearLayout.LayoutParams addwrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addwrap.setMargins(dp(14), dp(8), dp(14), 0);
        addb.setLayoutParams(addwrap);
        view.addView(addb);

        view.addView(section("Government apps & citizen services"));
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setPadding(dp(14), 0, dp(14), dp(4));
        int govtShown = 0;
        for (AppItem a : byCat("Government, e-Governance & Citizen Services")) {
            if (govtShown++ >= 12) break;
            h.addView(featCard(a));
        }
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(h);
        view.addView(hsv);

        view.addView(section("Suggested for you"));
        for (String n : SUGGESTED) for (AppItem a : repo.apps)
            if (a.name.startsWith(n)) { view.addView(row(a)); break; }

        view.addView(section("Top charts"));
        int n = Math.min(25, repo.apps.size());
        for (int i = 0; i < n; i++) view.addView(row(repo.apps.get(i)));
    }

    /** Renders list rows, capped so a long list never builds all at once. */
    private void addRows(List<AppItem> list) {
        int cap = showAll ? list.size() : Math.min(list.size(), 80);
        for (int i = 0; i < cap; i++) view.addView(row(list.get(i)));
        if (cap < list.size()) {
            TextView more = bold(tv("Show all " + list.size() + " apps", 14, 0xFFFFFFFF));
            more.setGravity(Gravity.CENTER);
            more.setPadding(dp(14), dp(13), dp(14), dp(13));
            more.setContentDescription("Show all apps");
            more.setBackground(bg(GREEN, 12, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(14), dp(12), dp(14), dp(12));
            more.setLayoutParams(lp);
            more.setOnClickListener(v -> { showAll = true; render(); });
            view.addView(more);
        }
    }

    private void renderCategories() {
        view.addView(section("Categories"));
        for (String c : repo.categories) {
            if ("All".equals(c)) continue;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(16), dp(12), dp(16), dp(12));
            List<AppItem> inCat = byCat(c);
            TextView dot = bold(tv(String.valueOf(inCat.size()), 13, 0xFFFFFFFF));
            dot.setGravity(Gravity.CENTER);
            dot.setBackground(bg(inCat.isEmpty() ? SURFACE2 : appColor(inCat.get(0)), 10, 0, 0));
            r.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(36)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(bold(tv(c, 14, TEXT)));
            col.addView(tv(inCat.size() + " apps", 12, MUTED));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = dp(12);
            r.addView(col, clp);
            r.addView(tv("\u203A", 20, MUTED));
            final String cat = c;
            r.setOnClickListener(v -> { category = cat; chip = "top"; showAll = false; render(); });
            view.addView(r);
        }
    }

    private View row(final AppItem a) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        r.addView(iconView(a, 46), new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView nm = bold(tv(a.name, 14.5f, TEXT));
        nm.setSingleLine(true); nm.setEllipsize(TextUtils.TruncateAt.END);
        nameRow.addView(nm);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.leftMargin = dp(6);
        if (showSwBadge(a)) nameRow.addView(swBadge(), sblp);
        if (isInstalled(a)) {
            LinearLayout.LayoutParams iblp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            iblp.leftMargin = dp(6);
            nameRow.addView(instBadge(), iblp);
        }
        col.addView(nameRow);
        TextView sub = tv(a.category, 12, MUTED);
        sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(sub);
        col.addView(tv(statLine(a), 12, MUTED));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(12);
        r.addView(col, clp);
        r.addView(tv("\u203A", 20, MUTED));
        r.setOnClickListener(v -> openApp(a));
        return r;
    }

    private String statLine(AppItem a) {
        StringBuilder s = new StringBuilder();
        if (notEmpty(a.subcategory)) s.append(a.subcategory);
        else if (notEmpty(a.developer)) s.append(a.developer);
        else if (!a.tags.isEmpty()) s.append(a.tags.get(a.tags.size() - 1));
        return s.toString();
    }

    private View featCard(final AppItem a) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(bg(SURFACE, 16, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(iconView(a, 56), new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout fname = new LinearLayout(this);
        fname.setOrientation(LinearLayout.HORIZONTAL);
        fname.setGravity(Gravity.CENTER_VERTICAL);
        fname.addView(bold(tv(a.name, 15, TEXT)));
        LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fblp.leftMargin = dp(6);
        if (showSwBadge(a)) fname.addView(swBadge(), fblp);
        tcol.addView(fname);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        top.addView(tcol, tlp);
        card.addView(top);

        TextView desc = tv(a.description, 12, MUTED);
        desc.setMaxLines(2); desc.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(8);
        card.addView(desc, dlp);

        LinearLayout stat = new LinearLayout(this);
        stat.setOrientation(LinearLayout.HORIZONTAL);
        stat.setGravity(Gravity.CENTER_VERTICAL);
        stat.addView(tv(statLine(a), 12, MUTED), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sv = bold(tv(saved.contains(a.id) ? "\u2605 Saved" : "\u2606 Save", 12, saved.contains(a.id) ? SAFFRON : MUTED));
        sv.setOnClickListener(v -> toggleSave(a.id));
        stat.addView(sv);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        card.addView(stat, slp);

        card.addView(storeButtons(a));
        return card;
    }

    private LinearLayout storeButtons(final AppItem a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        row.setLayoutParams(rlp);
        if (notEmpty(a.pkg) && isInstalledPkg(a.pkg)) addStore(row, "Open", a.pkg, 4, a);
        else if (notEmpty(a.playUrl)) addStore(row, "Play Store", a.playUrl, 1, a);
        if (notEmpty(a.indusUrl)) addStore(row, "Indus", a.indusUrl, 0, a);
        if (notEmpty(a.govtUrl)) addStore(row, "Govt", a.govtUrl, 0, a);
        String pkg = notEmpty(a.apkUrl) ? a.apkUrl : a.githubUrl;
        if (notEmpty(pkg)) addStore(row, notEmpty(a.apkUrl) ? "APK page" : "GitHub",
                pkg, notEmpty(a.apkUrl) ? 2 : 3, a);
        else if (notEmpty(a.websiteUrl)) addStore(row, "Website", a.websiteUrl, 0, a);
        return row;
    }
    /** kind: 0 outlined, 1 play(green), 2 in-app APK download, 3 github */
    private void addStore(LinearLayout row, String label, final String url, final int kind, final AppItem a) {
        TextView b = bold(tv(label, 11, (kind == 1 || kind == 3) ? 0xFFFFFFFF : TEXT));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        b.setBackground(kind == 1 ? bg(GREEN, 10, GREEN, 1)
                : kind == 3 ? bg(0xFF24292E, 10, 0xFF3A4048, 1)
                : bg(SURFACE2, 10, BORDER, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            if (kind == 4) {
                try {
                    Intent it = getPackageManager().getLaunchIntentForPackage(url);
                    if (it != null) startActivity(it); else toast("Cannot open");
                } catch (Throwable e) { toast("Cannot open"); }
            } else if (kind == 2) {
                // Open the official page in the browser. The app never downloads or installs
                // an APK itself, so it never prompts an install.
                openUrl(url);
            } else if (kind == 3) {
                showInApp(url, "GitHub \u00b7 " + a.name);
            } else {
                openUrl(url);
            }
        });
        row.addView(b);
    }

    /** Open a page inside the app (no external browser). */
    private void showInApp(String url, String title) {
        try {
            if (govtTitle != null) govtTitle.setText(title);
            govtOverlay.setVisibility(View.VISIBLE);
            if (webView != null) webView.loadUrl(url);
        } catch (Throwable e) { crashLog(e); openUrl(url); }
    }

    private void renderGovt() {
        List<AppItem> gov = new ArrayList<>();
        for (AppItem a : repo.apps)
            if ("Government, e-Governance & Citizen Services".equals(a.category)) gov.add(a);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(16), dp(12));
        bar.addView(bold(tv("Government apps (" + gov.size() + ")", 15, TEXT)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView open = bold(tv("Gov.in Appstore \u2197", 12, GREEN2));
        open.setPadding(dp(12), dp(8), dp(12), dp(8));
        open.setBackground(bg(SURFACE2, 10, BORDER, 1));
        open.setOnClickListener(v -> showInApp("https://apps.gov.in/All-Apps", "Gov.in Appstore"));
        bar.addView(open);
        view.addView(bar);
        java.util.LinkedHashMap<String, List<AppItem>> bySub = new java.util.LinkedHashMap<>();
        for (AppItem a : gov) {
            String k = (a.subcategory == null || a.subcategory.isEmpty()) ? "More government apps" : a.subcategory;
            List<AppItem> l = bySub.get(k);
            if (l == null) { l = new ArrayList<>(); bySub.put(k, l); }
            l.add(a);
        }
        for (java.util.Map.Entry<String, List<AppItem>> e : bySub.entrySet()) {
            view.addView(section(e.getKey() + "  (" + e.getValue().size() + ")"));
            for (AppItem a : e.getValue()) view.addView(row(a));
        }
    }

    private void renderGames() {
        List<AppItem> games = byCat("Gaming & Esports");
        view.addView(section("Games & esports"));
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setPadding(dp(14), 0, dp(14), dp(4));
        for (int i = 0; i < Math.min(6, games.size()); i++) h.addView(featCard(games.get(i)));
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(h);
        view.addView(hsv);
        view.addView(section("All games"));
        for (AppItem a : games) view.addView(row(a));
    }

    private void renderBooks() {
        List<AppItem> books = byCat("Media, Streaming & Audio");
        books.addAll(byCat("EdTech & Learning"));
        view.addView(section("Audiobooks, podcasts & learning"));
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setPadding(dp(14), 0, dp(14), dp(4));
        for (int i = 0; i < Math.min(6, books.size()); i++) h.addView(featCard(books.get(i)));
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(h);
        view.addView(hsv);
        view.addView(section("Browse all"));
        for (AppItem a : books) view.addView(row(a));
    }

    private void renderSearch() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(14), dp(6), dp(14), dp(4));
        EditText q = new EditText(this);
        q.setHint("Search Indian apps, developers, categories\u2026");
        q.setSingleLine(true);
        q.setText(query);
        q.setTextColor(TEXT);
        q.setHintTextColor(MUTED);
        q.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        q.setBackground(bg(SURFACE2, 999, 0, 0));
        q.setPadding(dp(16), dp(11), dp(16), dp(11));
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) {
                query = s.toString();
                searchHandler.removeCallbacks(searchRunnable);
                searchHandler.postDelayed(searchRunnable, 160);
            }
            public void afterTextChanged(Editable s) { }
        });
        wrap.addView(q, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        view.addView(wrap);
        drawResults();
    }
    private void drawResults() {
        while (view.getChildCount() > 1) view.removeViewAt(1);
        String q = query.trim().toLowerCase();
        List<AppItem> list = new ArrayList<>();
        if (q.isEmpty()) {
            for (int i = 0; i < Math.min(20, repo.apps.size()); i++) list.add(repo.apps.get(i));
        } else {
            for (AppItem a : repo.apps) {
                String hay = (a.name + " " + a.category + " " + a.description).toLowerCase();
                if (hay.contains(q)) list.add(a);
            }
        }
        String[] alt = findAlt(q);
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        if (alt != null) {
            AppItem p = appByName(alt[2]); if (p != null) found.add(p.id);
            if (!alt[3].isEmpty()) for (String nm : alt[3].split("\\|")) { AppItem a2 = appByName(nm); if (a2 != null) found.add(a2.id); }
        }
        for (AppItem a : list) found.add(a.id);
        if (!q.isEmpty()) {
            TextView fh = bold(tv(found.size() + " Indian apps found", 12.5f, GREEN2));
            fh.setPadding(dp(16), dp(12), dp(16), dp(6));
            view.addView(fh);
        }
        if (alt != null) {
            view.addView(altBanner(alt));
            view.addView(section("Made in India alternatives"));
            view.addView(altRow(alt[2]));
            if (!alt[3].isEmpty()) for (String nm : alt[3].split("\\|")) view.addView(altRow(nm));
        }
        view.addView(section(q.isEmpty() ? "Popular searches" : (alt != null ? "Other Indian apps" : "Results")));
        if (list.isEmpty()) {
            if (alt == null) {
                TextView e = tv("No Indian apps match \u201C" + query + "\u201D.", 14, MUTED);
                e.setGravity(Gravity.CENTER);
                e.setPadding(dp(20), dp(50), dp(20), dp(50));
                view.addView(e);
            }
            return;
        }
        int cap = Math.min(list.size(), MAX_RESULTS);
        for (int i = 0; i < cap; i++) view.addView(row(list.get(i)));
        if (list.size() > cap) {
            TextView more = tv("Showing first " + cap + " of " + list.size()
                    + " results. Keep typing to narrow it down.", 12.5f, MUTED);
            more.setPadding(dp(16), dp(12), dp(16), dp(20));
            view.addView(more);
        }
    }

    private void renderYou() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView av = bold(tv(initialsOf(userName), 26, 0xFFFFFFFF));
        av.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{SAFFRON, GREEN});
        av.setBackground(g);
        p.addView(av, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout pc = new LinearLayout(this);
        pc.setOrientation(LinearLayout.VERTICAL);
        pc.addView(bold(tv(userName.isEmpty() ? "Guest" : userName, 18, TEXT)));
        pc.addView(tv(userEmail.isEmpty() ? "Swadeshi Appstore \u00b7 Only Indian apps" : userEmail, 13, MUTED));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        plp.leftMargin = dp(14);
        p.addView(pc, plp);
        view.addView(p);

        LinearLayout mt = new LinearLayout(this);
        mt.setOrientation(LinearLayout.HORIZONTAL);
        mt.setGravity(Gravity.CENTER_VERTICAL);
        mt.setPadding(dp(16), dp(14), dp(16), dp(14));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_storage);
        ic.setColorFilter(GREEN2);
        LinearLayout icb = new LinearLayout(this);
        icb.setGravity(Gravity.CENTER);
        icb.setBackground(bg(SURFACE2, 12, 0, 0));
        icb.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
        mt.addView(icb, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout mcol = new LinearLayout(this);
        mcol.setOrientation(LinearLayout.VERTICAL);
        mcol.addView(bold(tv("Manage apps & device", 15, TEXT)));
        mcol.addView(tv("Storage, updates & Play Protect", 12.5f, MUTED));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(14);
        mt.addView(mcol, mlp);
        mt.addView(tv("\u203A", 20, MUTED));
        mt.setOnClickListener(v -> { mtab = "overview"; manageOverlay.setVisibility(View.VISIBLE); renderManage(); });
        view.addView(mt);

        LinearLayout scard = new LinearLayout(this);
        scard.setOrientation(LinearLayout.VERTICAL);
        scard.setBackground(bg(SURFACE, 16, 0, 0));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.setMargins(dp(14), dp(4), dp(14), 0);
        scard.setLayoutParams(sclp);
        scard.addView(tile(R.drawable.ic_settings, "Settings & appearance"));
        scard.getChildAt(scard.getChildCount() - 1).setOnClickListener(v -> openSettings());
        scard.addView(tile(R.drawable.ic_you, "Sign out"));
        scard.getChildAt(scard.getChildCount() - 1).setOnClickListener(v -> {
            userName = ""; userEmail = "";
            prefs.edit().remove("user_name").remove("user_email").apply();
            loginOverlay.setVisibility(View.VISIBLE);
        });
        scard.addView(tile(R.drawable.ic_bookmark_remove, "Clear saved apps"));
        scard.getChildAt(scard.getChildCount() - 1).setOnClickListener(v -> { saved.clear(); persist(); render(); toast("Saved apps cleared"); });
        scard.addView(tile(R.drawable.ic_info, "About Swadeshi Appstore \u00b7 v" + appVersion()));
        scard.getChildAt(scard.getChildCount() - 1).setOnClickListener(v -> showAbout());
        scard.addView(tile(R.drawable.ic_support, "Support & contact"));
        scard.getChildAt(scard.getChildCount() - 1).setOnClickListener(v -> showSupport());
        scard.addView(tile(R.drawable.ic_tip, "Send a tip on UPI"));
        scard.getChildAt(scard.getChildCount() - 1).setOnClickListener(v -> showTipSheet());
        scard.addView(tile(R.drawable.ic_apps, "Add an Indian app"));
        scard.getChildAt(scard.getChildCount() - 1).setOnClickListener(v -> showAddAppForm());
        if (BuildConfig.DEBUG) {
            scard.addView(tile(R.drawable.ic_settings, "Debug menu [TEST]"));
            scard.getChildAt(scard.getChildCount() - 1).setOnClickListener(v -> openDebugMenu());
        }
        view.addView(scard);

        List<AppItem> mine = new ArrayList<>();
        for (AppItem a : repo.apps) if (localIds.contains(a.id)) mine.add(a);
        if (!mine.isEmpty()) {
            view.addView(section("Apps you added  (" + mine.size() + ")"));
            LinearLayout ml = new LinearLayout(this);
            ml.setOrientation(LinearLayout.VERTICAL);
            ml.setBackground(bg(SURFACE, 16, 0, 0));
            LinearLayout.LayoutParams mllp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mllp.setMargins(dp(14), dp(4), dp(14), dp(16));
            ml.setLayoutParams(mllp);
            for (final AppItem a : mine) {
                LinearLayout r = new LinearLayout(this);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(12), dp(10), dp(12), dp(10));
                r.addView(iconView(a, 40), new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.addView(bold(tv(a.name, 14, TEXT)));
                col.addView(tv(notEmpty(a.category) ? a.category : "Added by you", 12, MUTED));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.leftMargin = dp(12);
                r.addView(col, clp);
                TextView rm = bold(tv("Remove", 12, RED));
                rm.setPadding(dp(10), dp(8), dp(10), dp(8));
                rm.setContentDescription("Remove " + a.name);
                rm.setOnClickListener(v -> removeLocalApp(a));
                r.addView(rm);
                r.setContentDescription(a.name);
                r.setOnClickListener(v -> openApp(a));
                ml.addView(r);
            }
            view.addView(ml);
        }

        view.addView(section("Legal"));
        LinearLayout legal = new LinearLayout(this);
        legal.setOrientation(LinearLayout.VERTICAL);
        legal.setBackground(bg(SURFACE, 16, 0, 0));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.setMargins(dp(14), dp(4), dp(14), dp(16));
        legal.setLayoutParams(llp);
        String[][] docs = {
            {"Privacy Policy", LEGAL_PRIVACY, "privacy"},
            {"Terms and Conditions", LEGAL_TERMS, "terms"},
            {"Refund Policy", LEGAL_REFUND, "refund"},
            {"Cookie Policy", LEGAL_COOKIES, "cookie"},
            {"Accessibility Statement", LEGAL_ACCESSIBILITY, "accessibility"},
            {"Security Policy", LEGAL_SECURITY, "security"},
            {"Responsible Disclosure", LEGAL_DISCLOSURE, "disclosure"}
        };
        for (String[] doc : docs) {
            View rowv = tile(legalIcon(doc[2]), doc[0]);
            final String dt = doc[0], db = doc[1];
            rowv.setContentDescription(doc[0]);
            rowv.setOnClickListener(v -> showLegal(dt, db));
            legal.addView(rowv);
        }
        view.addView(legal);

        List<AppItem> sv = new ArrayList<>();
        for (AppItem a : repo.apps) if (saved.contains(a.id)) sv.add(a);
        view.addView(section("Saved apps  (" + sv.size() + ")"));
        if (sv.isEmpty()) {
            TextView e = tv("Tap \u2606 Save on any app to bookmark it here.", 14, MUTED);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(20), dp(40), dp(20), dp(16));
            view.addView(e);
            TextView go = bold(tv("Browse apps", 14, 0xFFFFFFFF));
            go.setGravity(Gravity.CENTER);
            go.setPadding(dp(14), dp(13), dp(14), dp(13));
            go.setContentDescription("Browse apps");
            go.setBackground(bg(GREEN, 12, 0, 0));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.setMargins(dp(14), 0, dp(14), dp(16));
            go.setLayoutParams(glp);
            go.setOnClickListener(v -> { tab = "apps"; chip = "top"; category = null; render(); });
            view.addView(go);
        } else {
            for (AppItem a : sv) view.addView(row(a));
        }
    }

    // ---------------- manage overlay ----------------
    private void renderManage() {
        try { renderManageInner(); }
        catch (Throwable e) {
            crashLog(e);
            try {
                manageBody.removeAllViews();
                TextView h = bold(tv("Manage screen error", 16, TEXT));
                h.setPadding(dp(16), dp(24), dp(16), dp(6));
                manageBody.addView(h);
                TextView d = tv(String.valueOf(e), 12, MUTED);
                d.setPadding(dp(16), 0, dp(16), dp(24));
                manageBody.addView(d);
            } catch (Throwable ignored) { }
        }
    }
    private void renderManageInner() {
        overviewTab.setTextColor("overview".equals(mtab) ? GREEN2 : MUTED);
        manageTab.setTextColor("manage".equals(mtab) ? GREEN2 : MUTED);
        manageBody.removeAllViews();
        if ("overview".equals(mtab)) {
            LinearLayout c1 = new LinearLayout(this);
            c1.setOrientation(LinearLayout.HORIZONTAL);
            c1.setGravity(Gravity.CENTER_VERTICAL);
            c1.setPadding(dp(16), dp(16), dp(16), dp(16));
            c1.setBackground(bg(SURFACE, 16, 0, 0));
            ImageView sh = new ImageView(this);
            sh.setImageResource(R.drawable.ic_shield);
            sh.setColorFilter(SAFFRON);
            LinearLayout shb = new LinearLayout(this);
            shb.setGravity(Gravity.CENTER);
            shb.setBackground(bg(0xFF3A2A12, 12, 0, 0));
            shb.addView(sh, new LinearLayout.LayoutParams(dp(22), dp(22)));
            c1.addView(shb, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout cc = new LinearLayout(this);
            cc.setOrientation(LinearLayout.VERTICAL);
            cc.addView(bold(tv("Play Protect and device safety", 15, TEXT)));
            cc.addView(tv("Check this device, and open Google's own Play Protect screen.", 13, MUTED));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = dp(12);
            c1.addView(cc, clp);
            c1.setContentDescription("Play Protect and device safety");
            c1.setOnClickListener(v -> showSafetyScan());
            addCard(c1);

            LinearLayout c2 = new LinearLayout(this);
            c2.setOrientation(LinearLayout.VERTICAL);
            c2.setPadding(dp(16), dp(16), dp(16), dp(16));
            c2.setBackground(bg(SURFACE, 16, 0, 0));
            c2.addView(bold(tv("Storage", 15, TEXT)));
            final long[] st = storageStats();
            android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress((int) st[2]);
            if (pb.getProgressDrawable() != null)
                pb.getProgressDrawable().setColorFilter(SAFFRON, android.graphics.PorterDuff.Mode.SRC_IN);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
            plp.topMargin = dp(12);
            plp.bottomMargin = dp(8);
            c2.addView(pb, plp);
            c2.addView(tv(fmtBytes(st[0]) + " of " + fmtBytes(st[1]) + " used", 13, MUTED));
            c2.setContentDescription("Storage settings");
            c2.setOnClickListener(v -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); }
                catch (Throwable e) { toast("Storage settings unavailable"); }
            });
            addCard(c2);

            LinearLayout c3 = new LinearLayout(this);
            c3.setOrientation(LinearLayout.VERTICAL);
            c3.setPadding(dp(16), dp(16), dp(16), dp(16));
            c3.setBackground(bg(SURFACE, 16, 0, 0));
            c3.addView(bold(tv("Installed apps", 15, TEXT)));
            c3.addView(tv(installedCatalogCount() + " Swadeshi apps installed on this device", 13, MUTED));
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(12);
            TextView u1 = bold(tv("Refresh", 13, 0xFFFFFFFF));
            u1.setPadding(dp(16), dp(10), dp(16), dp(10));
            u1.setContentDescription("Refresh installed apps");
            u1.setBackground(bg(GREEN, 10, GREEN, 1));
            TextView u2 = bold(tv("See details", 13, TEXT));
            u2.setPadding(dp(16), dp(10), dp(16), dp(10));
            u2.setContentDescription("See installed app details");
            u2.setBackground(bg(SURFACE2, 10, BORDER, 1));
            LinearLayout.LayoutParams u1lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            u1lp.rightMargin = dp(10);
            u1.setOnClickListener(v -> {
                u1.setText("Refreshing\u2026");
                u1.setEnabled(false);
                installedKeys = null;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::renderManage, 400);
            });
            u2.setOnClickListener(v -> showInstalledDetails());
            btns.addView(u1, u1lp);
            btns.addView(u2);
            c3.addView(btns, blp);
            addCard(c3);

            LinearLayout c4 = new LinearLayout(this);
            c4.setOrientation(LinearLayout.VERTICAL);
            c4.setPadding(dp(6), dp(6), dp(6), dp(6));
            c4.setBackground(bg(SURFACE, 16, 0, 0));
            c4.addView(tile(R.drawable.ic_sync, "Sync apps to devices"));
            c4.addView(tile(R.drawable.ic_star, "Ratings and reviews"));
            c4.addView(tile(R.drawable.ic_contrib, "Your contributions"));
            c4.getChildAt(0).setOnClickListener(v -> showSync());
            c4.getChildAt(1).setOnClickListener(v -> showRatings());
            c4.getChildAt(2).setOnClickListener(v -> showContributions());
            c4.addView(tile(R.drawable.ic_apps, "Check my phone"));
            c4.getChildAt(3).setOnClickListener(v -> showPhoneCheck());
            addCard(c4);
        } else {
            renderManageList();
        }
    }

    // the Manage tab. The heavy work runs off the main thread and rows arrive in
    // small batches, so the tab opens instantly instead of freezing.
    private void renderManageList() {
        final int token = ++manageToken;
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(14), dp(12), dp(14), dp(6));
        String[][] cd = {{"this", "This device"}, {"swadeshi", "Swadeshi"}, {"games", "Games"}, {"all", "All apps"}};
        for (int i = 0; i < cd.length; i++) {
            final String key = cd[i][0];
            boolean on = key.equals(manageFilter);
            TextView c = bold(tv(cd[i][1], 13, on ? 0xFFFFFFFF : TEXT));
            c.setPadding(dp(14), dp(10), dp(14), dp(10));
            c.setContentDescription(cd[i][1]);
            c.setBackground(on ? bg(GREEN, 999, GREEN, 1) : bg(SURFACE, 999, BORDER, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> { manageFilter = key; renderManage(); });
            chips.addView(c);
        }
        hs.addView(chips);
        manageBody.addView(hs);

        final TextView loading = tv("Reading installed apps from your device\u2026", 13, MUTED);
        loading.setPadding(dp(16), dp(16), dp(16), dp(16));
        manageBody.addView(loading);

        final List<android.content.pm.ApplicationInfo> all = installedApps();
        final String filter = manageFilter;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<android.content.pm.ApplicationInfo> keep = new ArrayList<>();
                for (android.content.pm.ApplicationInfo ai : all) {
                    if ("swadeshi".equals(filter) && !isSwadeshi(ai)) continue;
                    if ("games".equals(filter) && !isGame(ai)) continue;
                    keep.add(ai);
                    if (keep.size() >= MAX_MANAGE_ROWS) break;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (token != manageToken) return;
                        loading.setVisibility(View.GONE);
                        buildManageRows(keep, token);
                    }
                });
            }
        }).start();
    }

    private void buildManageRows(final List<android.content.pm.ApplicationInfo> apps, final int token) {
        final android.content.pm.PackageManager pm = getPackageManager();
        TextView info = tv("Read from your device \u00b7 " + apps.size() + " apps \u00b7 nothing leaves your phone", 12, MUTED);
        info.setPadding(dp(16), dp(8), dp(16), dp(4));
        manageBody.addView(info);
        selCount = 0;
        selBar = bold(tv("", 13, 0xFF6DDCB0));
        selBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        selBar.setBackground(bg(0xFF10301D, 12, 0xFF2E7D4F, 1));
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbp.setMargins(dp(14), dp(10), dp(14), 0);
        selBar.setVisibility(View.GONE);
        manageBody.addView(selBar, sbp);
        TextView hdr = bold(tv("Apps (" + apps.size() + ")", 16, TEXT));
        hdr.setPadding(dp(16), dp(8), dp(16), dp(8));
        manageBody.addView(hdr);
        if (apps.isEmpty()) {
            TextView em = tv("No matching apps installed on this device.", 14, MUTED);
            em.setGravity(Gravity.CENTER);
            em.setPadding(dp(20), dp(50), dp(20), dp(50));
            manageBody.addView(em);
            return;
        }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int CHUNK = 8;
        final int[] idx = {0};
        h.post(new Runnable() {
            @Override public void run() {
                if (token != manageToken) return;
                int end = Math.min(idx[0] + CHUNK, apps.size());
                for (int i = idx[0]; i < end; i++) manageBody.addView(manageRow(apps.get(i), pm));
                idx[0] = end;
                if (idx[0] < apps.size()) h.post(this);
            }
        });
    }

    private View manageRow(final android.content.pm.ApplicationInfo ai,
                           final android.content.pm.PackageManager pm) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        ImageView ic = new ImageView(this);
        try {
            android.graphics.drawable.Drawable d = installedIconCache.get(ai.packageName);
            if (d == null) { d = ai.loadIcon(pm); installedIconCache.put(ai.packageName, d); }
            ic.setImageDrawable(d);
        } catch (Throwable e) { }
        r.addView(ic, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        String label = "";
        try { label = String.valueOf(ai.loadLabel(pm)); } catch (Throwable e) { }
        if (label.trim().isEmpty()) label = ai.packageName;
        String sz = "-";
        String ver = "1.0";
        long upd = 0;
        try {
            if (ai.sourceDir != null) sz = fmtBytes(new java.io.File(ai.sourceDir).length());
            android.content.pm.PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
            if (pi.versionName != null) ver = pi.versionName;
            upd = pi.lastUpdateTime;
        } catch (Throwable e) { }
        TextView nm = bold(tv(label + " \u00b7 " + sz, 14.5f, TEXT));
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm);
        col.addView(tv("Updated " + ago(upd) + " \u00b7 v" + ver, 12, MUTED));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(12);
        r.addView(col, clp);
        if (isSwadeshi(ai)) {
            TextView chip = bold(tv("Swadeshi", 10, GREEN2));
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            chip.setBackground(bg(SURFACE2, 999, GREEN2, 1));
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chp.rightMargin = dp(8);
            r.addView(chip, chp);
        }
        final TextView chev = tv("\u2304", 20, MUTED);
        chev.setPadding(dp(8), 0, dp(8), 0);
        chev.setContentDescription("Show details for " + label);
        r.addView(chev);
        TextView cb = tv("", 14, 0xFFFFFFFF);
        cb.setGravity(Gravity.CENTER);
        cb.setBackground(bg(0x00000000, 6, MUTED, 2));
        cb.setContentDescription("Select " + label);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(dp(26), dp(26));
        cblp.leftMargin = dp(12);
        final TextView cbf = cb;
        cb.setOnClickListener(v -> {
            boolean on = cbf.getTag() != null;
            cbf.setTag(on ? null : "1");
            cbf.setText(on ? "" : "\u2713");
            cbf.setBackground(on ? bg(0x00000000, 6, MUTED, 2) : bg(GREEN2, 6, GREEN2, 2));
            selCount += on ? -1 : 1;
            if (selCount < 0) selCount = 0;
            if (selBar != null) {
                selBar.setText(selCount + " app" + (selCount == 1 ? "" : "s") + " selected");
                selBar.setVisibility(selCount > 0 ? View.VISIBLE : View.GONE);
            }
        });
        r.addView(cb, cblp);
        r.setOnClickListener(v -> cb.performClick());

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(r);
        final LinearLayout det = appDetail(ai.packageName, ver, sz, upd);
        det.setVisibility(View.GONE);
        wrap.addView(det);
        chev.setOnClickListener(v -> {
            boolean vis = det.getVisibility() == View.VISIBLE;
            det.setVisibility(vis ? View.GONE : View.VISIBLE);
            chev.setText(vis ? "\u2304" : "\u2303");
        });
        return wrap;
    }

    private void addCard(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(14), dp(12), dp(14), 0);
        v.setLayoutParams(lp);
        manageBody.addView(v);
    }
    private View tile(int icon, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(12), dp(10), dp(12));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(GREEN2);
        LinearLayout ib = new LinearLayout(this);
        ib.setGravity(Gravity.CENTER);
        ib.setBackground(bg(SURFACE2, 12, 0, 0));
        ib.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        r.addView(ib, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(14);
        r.addView(bold(tv(label, 15, TEXT)), lp);
        return r;
    }

    // ---------------- app detail sheet ----------------
    // ---------------- developer page ----------------
    private int countByDeveloper(String dev) {
        if (dev == null || dev.trim().isEmpty()) return 0;
        int n = 0;
        for (AppItem x : repo.apps) {
            if (x.developer != null && x.developer.trim().equalsIgnoreCase(dev.trim())) n++;
        }
        return n;
    }

    private List<AppItem> byDeveloper(String dev) {
        List<AppItem> out = new ArrayList<>();
        if (dev == null || dev.trim().isEmpty()) return out;
        for (AppItem x : repo.apps) {
            if (x.developer != null && x.developer.trim().equalsIgnoreCase(dev.trim())) out.add(x);
        }
        java.util.Collections.sort(out, new java.util.Comparator<AppItem>() {
            public int compare(AppItem p, AppItem q) {
                return p.name.compareToIgnoreCase(q.name);
            }
        });
        return out;
    }

    // ---------------- developer page (full page) ----------------

    /** A blank full-screen page with a back header. Returns {page, body}; the title is the page tag. */
    private LinearLayout[] makePage(String initialTitle) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setVisibility(View.GONE);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(10), dp(12), dp(10));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(TEXT);
        LinearLayout backBtn = new LinearLayout(this);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.addView(back, new LinearLayout.LayoutParams(dp(22), dp(22)));
        backBtn.setContentDescription("Back");
        backBtn.setOnClickListener(v -> page.setVisibility(View.GONE));
        head.addView(backBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = bold(tv(initialTitle, 18, TEXT));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(head);
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(24));
        sv.addView(body);
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.setTag(title);
        return new LinearLayout[]{page, body};
    }

    /** Play-style developer page: banner, description, featured app, then All apps. */
    private void showDeveloperPage(final String dev) {
        devOpen = dev;
        LinearLayout body = devBody;
        if (body == null) return;
        body.removeAllViews();
        if (devTitle != null) devTitle.setText(dev);

        final List<AppItem> apps = byDeveloper(dev);

        // banner strip
        LinearLayout banner = new LinearLayout(this);
        banner.setGravity(Gravity.CENTER);
        banner.setBackgroundColor(0xFF12203A);
        TextView initial = bold(tv(dev.isEmpty() ? "?" : dev.substring(0, 1).toUpperCase(Locale.US), 30, 0xFFFFFFFF));
        initial.setGravity(Gravity.CENTER);
        initial.setBackground(bg(GREEN2, 999, 0, 0));
        banner.addView(initial, new LinearLayout.LayoutParams(dp(64), dp(64)));
        body.addView(banner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));

        // name and description
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(16), dp(16), dp(16), dp(8));
        head.addView(bold(tv(dev, 20, TEXT)));
        head.addView(tv(apps.size() + (apps.size() == 1 ? " app" : " apps")
                + " in this store, made in India", 13, MUTED));
        body.addView(head);

        // featured app
        if (!apps.isEmpty()) {
            TextView fh = bold(tv("Featured app", 16, TEXT));
            fh.setPadding(dp(16), dp(18), dp(16), dp(6));
            body.addView(fh);
            AppItem f = apps.get(0);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.setBackground(bg(SURFACE, 16, BORDER, 1));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(dp(14), 0, dp(14), 0);
            card.setLayoutParams(clp);
            card.addView(iconView(f, 60), new LinearLayout.LayoutParams(dp(60), dp(60)));
            LinearLayout fc = new LinearLayout(this);
            fc.setOrientation(LinearLayout.VERTICAL);
            TextView fn = bold(tv(f.name, 15, TEXT));
            fn.setSingleLine(true);
            fn.setEllipsize(TextUtils.TruncateAt.END);
            fc.addView(fn);
            fc.addView(tv(f.category, 12, MUTED));
            TextView inst = tv(isInstalled(f) ? "Installed on this device" : "Tap to open", 12, GREEN2);
            inst.setPadding(0, dp(4), 0, 0);
            fc.addView(inst);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            flp.leftMargin = dp(12);
            card.addView(fc, flp);
            card.addView(tv("\u203A", 20, MUTED));
            card.setContentDescription(f.name);
            card.setOnClickListener(v -> openApp(f));
            body.addView(card);
        }

        // All apps row that opens the grid page
        LinearLayout allRow = new LinearLayout(this);
        allRow.setOrientation(LinearLayout.HORIZONTAL);
        allRow.setGravity(Gravity.CENTER_VERTICAL);
        allRow.setPadding(dp(16), dp(18), dp(16), dp(10));
        allRow.addView(bold(tv("All apps", 16, TEXT)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        allRow.addView(tv("See all " + apps.size(), 13, GREEN2));
        allRow.addView(tv("  \u203A", 18, GREEN2));
        allRow.setContentDescription("All apps by " + dev);
        allRow.setOnClickListener(v -> showAllAppsByDeveloper(dev));
        body.addView(allRow);

        // a preview strip of the first few, like the store shows
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(dp(14), 0, dp(14), dp(6));
        int shown = Math.min(6, apps.size());
        for (int k = 0; k < shown; k++) {
            final AppItem x = apps.get(k);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(6), dp(6), dp(6), dp(6));
            cell.addView(iconView(x, 62), new LinearLayout.LayoutParams(dp(62), dp(62)));
            TextView nm = tv(x.name, 11, MUTED);
            nm.setMaxLines(2);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            nm.setGravity(Gravity.CENTER_HORIZONTAL);
            nm.setPadding(0, dp(6), 0, 0);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(96),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cell.addView(nm, nlp);
            cell.setContentDescription(x.name);
            cell.setOnClickListener(v -> openApp(x));
            strip.addView(cell);
        }
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(strip);
        body.addView(hsv);

        allOverlay.setVisibility(View.GONE);
        devOverlay.setVisibility(View.VISIBLE);
    }

    /** The All apps grid page, three columns, like the store. */
    private void showAllAppsByDeveloper(final String dev) {
        LinearLayout body = allBody;
        if (body == null) return;
        body.removeAllViews();
        if (allTitle != null) allTitle.setText("All apps");

        final List<AppItem> apps = byDeveloper(dev);
        TextView sub = tv(apps.size() + " apps by " + dev, 13, MUTED);
        sub.setPadding(dp(16), dp(4), dp(16), dp(14));
        body.addView(sub);

        int cols = 3;
        LinearLayout row = null;
        for (int k = 0; k < apps.size(); k++) {
            if (k % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(10), 0, dp(10), 0);
                body.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final AppItem x = apps.get(k);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(6), dp(10), dp(6), dp(14));
            cell.addView(iconView(x, 64), new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView nm = bold(tv(x.name, 12, TEXT));
            nm.setMaxLines(2);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            nm.setGravity(Gravity.CENTER_HORIZONTAL);
            nm.setPadding(0, dp(8), 0, 0);
            cell.addView(nm, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView meta = tv(isInstalled(x) ? "Installed" : x.category, 11, MUTED);
            meta.setMaxLines(1);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            meta.setGravity(Gravity.CENTER_HORIZONTAL);
            meta.setPadding(0, dp(2), 0, 0);
            cell.addView(meta, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.setContentDescription(x.name);
            cell.setOnClickListener(v -> openApp(x));
            row.addView(cell, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        allOverlay.setVisibility(View.VISIBLE);
    }

    private void openApp(final AppItem a) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));
        box.setBackground(bg(SURFACE, 20, BORDER, 1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(iconView(a, 56), new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(bold(tv(a.name, 19, TEXT)));
        col.addView(tv(a.category, 13, MUTED));
        col.addView(tv(statLine(a), 13, MUTED));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(14);
        top.addView(col, clp);
        box.addView(top);

        // Developer page entry: shown only when this developer has more than one app here.
        final String devName = a.developer == null ? "" : a.developer.trim();
        final int devCount = countByDeveloper(devName);
        if (devName.length() > 0 && devCount > 1) {
            LinearLayout devRow = new LinearLayout(this);
            devRow.setOrientation(LinearLayout.HORIZONTAL);
            devRow.setGravity(Gravity.CENTER_VERTICAL);
            devRow.setPadding(dp(14), dp(12), dp(14), dp(12));
            devRow.setBackground(bg(SURFACE2, 12, BORDER, 1));
            LinearLayout dv = new LinearLayout(this);
            dv.setOrientation(LinearLayout.VERTICAL);
            TextView dn = bold(tv(devName, 14, TEXT));
            dn.setSingleLine(true);
            dn.setEllipsize(TextUtils.TruncateAt.END);
            dv.addView(dn);
            dv.addView(tv(devCount + " apps in this store", 12, MUTED));
            LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            devRow.addView(dv, dvp);
            devRow.addView(tv("\u203A", 20, MUTED));
            LinearLayout.LayoutParams drlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            drlp.topMargin = dp(12);
            devRow.setLayoutParams(drlp);
            devRow.setOnClickListener(v -> { d.dismiss(); showDeveloperPage(devName); });
            box.addView(devRow);
        }

        TextView desc = tv(a.description, 14, TEXT);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(14);
        box.addView(desc, dlp);

        box.addView(storeButtons(a));

        TextView save = bold(tv(saved.contains(a.id) ? "\u2713 Saved to favourites" : "\u2606 Save to favourites", 14, TEXT));
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(14), dp(12), dp(14), dp(12));
        save.setBackground(bg(SURFACE2, 12, BORDER, 1));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        save.setLayoutParams(slp);
        save.setOnClickListener(v -> { toggleSave(a.id); openApp(a); });
        box.addView(save);

        TextView report = bold(tv("Report this app", 14, TEXT));
        report.setGravity(Gravity.CENTER);
        report.setPadding(dp(14), dp(12), dp(14), dp(12));
        report.setBackground(bg(SURFACE2, 12, BORDER, 1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        report.setLayoutParams(rlp);
        report.setContentDescription("Report " + a.name);
        report.setOnClickListener(v -> { d.dismiss(); showReportSheet(a); });
        box.addView(report);

        TextView close = tv("Close", 14, MUTED);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(12), dp(10), dp(4));
        close.setOnClickListener(v -> d.dismiss());
        box.addView(close);

        d.setContentView(box);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(bg(0x00000000, 0, 0, 0));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
    }

    /** Offline "Report this app" flow: pick a reason, add a note, send it by email. No server. */
    private void showReportSheet(final AppItem a) {
        final String[] REASONS = {
                "Not an Indian app (fake Swadeshi claim)",
                "Data or privacy violation",
                "Cloned or fake application",
                "Malware or suspicious activity",
                "Inappropriate content"
        };
        final String[] TYPES = {
                "NOT_INDIAN_APP", "PRIVACY_VIOLATION", "CLONED_APP", "MALWARE", "INAPPROPRIATE"
        };

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(18));
        box.setBackground(bg(SURFACE, 20, BORDER, 1));

        box.addView(bold(tv("Report this app", 18, TEXT)));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(2);
        box.addView(tv(a.name, 13, MUTED), nlp);

        TextView why = bold(tv("Why are you reporting this?", 14, TEXT));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(14);
        box.addView(why, wlp);

        final android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        group.setOrientation(android.widget.RadioGroup.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(6);
        group.setLayoutParams(glp);
        for (int i = 0; i < REASONS.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(REASONS[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            rb.setId(i + 1);
            rb.setPadding(dp(4), dp(8), dp(4), dp(8));
            group.addView(rb);
        }
        box.addView(group);

        final EditText note = new EditText(this);
        note.setHint("Add details (optional)");
        note.setHintTextColor(MUTED);
        note.setTextColor(TEXT);
        note.setBackground(bg(SURFACE2, 12, BORDER, 1));
        note.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams nlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp2.topMargin = dp(12);
        box.addView(note, nlp2);

        TextView hint = tv("Reports reach the developer by email. No account and no server involved.", 11.5f, MUTED);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        box.addView(hint, hlp);

        TextView submit = bold(tv("Submit report", 14, 0xFFFFFFFF));
        submit.setGravity(Gravity.CENTER);
        submit.setPadding(dp(14), dp(13), dp(14), dp(13));
        submit.setBackground(bg(GREEN, 12, 0, 0));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(14);
        submit.setLayoutParams(slp);
        submit.setOnClickListener(v -> {
            int sel = group.getCheckedRadioButtonId();
            if (sel < 1 || sel > REASONS.length) { toast("Please choose a reason"); return; }
            int idx = sel - 1;
            String comment = note.getText().toString().trim();
            String body = buildReportBody(a, TYPES[idx], REASONS[idx], comment);
            try {
                Intent i = new Intent(Intent.ACTION_SENDTO);
                i.setData(Uri.parse("mailto:" + DEV_EMAIL));
                i.putExtra(Intent.EXTRA_SUBJECT, "App report: " + a.name);
                i.putExtra(Intent.EXTRA_TEXT, body);
                startActivity(i);
                d.dismiss();
            } catch (Throwable e) {
                toast("No email app found");
            }
        });
        box.addView(submit);

        TextView cancel = tv("Cancel", 14, MUTED);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(10), dp(12), dp(10), dp(4));
        cancel.setOnClickListener(v -> d.dismiss());
        box.addView(cancel);

        d.setContentView(box);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(bg(0x00000000, 0, 0, 0));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
    }

    /** Report payload (JSON, the agreed shape) plus a readable header, sent by email. */
    private String buildReportBody(AppItem a, String type, String reason, String comment) {
        String ts;
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            ts = fmt.format(new java.util.Date());
        } catch (Throwable e) { ts = ""; }
        String payload;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("user_id", userEmail == null || userEmail.isEmpty() ? "guest" : userEmail);
            o.put("app_id", a.id);
            o.put("app_name", a.name);
            o.put("package", a.pkg == null ? "" : a.pkg);
            o.put("report_type", type);
            o.put("reason", reason);
            o.put("description", comment == null ? "" : comment);
            o.put("timestamp", ts);
            payload = o.toString(2);
        } catch (Throwable e) {
            payload = "{\"app_id\":\"" + a.id + "\",\"report_type\":\"" + type + "\"}";
        }
        return "App report from Swadeshi Appstore\n\n"
                + "App: " + a.name + "\n"
                + "Type: " + type + "\n"
                + "Reason: " + reason + "\n"
                + (comment.isEmpty() ? "" : "Details: " + comment + "\n")
                + "\n--- machine readable ---\n" + payload + "\n";
    }

    // ---------------- real device data ----------------
    private List<android.content.pm.ApplicationInfo> installedApps() {
        android.content.pm.PackageManager pm = getPackageManager();
        List<android.content.pm.ApplicationInfo> out = new java.util.ArrayList<>();
        try {
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                out.add(ai);
            }
            final android.content.pm.PackageManager pmf = pm;
            java.util.Collections.sort(out, new java.util.Comparator<android.content.pm.ApplicationInfo>() {
                public int compare(android.content.pm.ApplicationInfo a, android.content.pm.ApplicationInfo b) {
                    return String.valueOf(a.loadLabel(pmf)).compareToIgnoreCase(String.valueOf(b.loadLabel(pmf)));
                }
            });
        } catch (Throwable e) {}
        if (out.size() > 160) out = new java.util.ArrayList<>(out.subList(0, 160));
        return out;
    }
    private LinearLayout appDetail(final String pkg, String ver, String sz, long upd) {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setPadding(dp(74), 0, dp(16), dp(14));
        d.addView(tv("Package: " + pkg, 12, MUTED));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(2);
        d.addView(tv("Version " + ver + "  \u00b7  " + sz + "  \u00b7  Updated " + ago(upd), 12, MUTED), vlp);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        TextView b1 = bold(tv("Open", 12, 0xFFFFFFFF));
        b1.setPadding(dp(14), dp(8), dp(14), dp(8));
        b1.setBackground(bg(GREEN, 10, GREEN, 1));
        b1.setOnClickListener(v -> {
            try {
                Intent it = getPackageManager().getLaunchIntentForPackage(pkg);
                if (it != null) startActivity(it); else toast("Cannot open");
            } catch (Throwable e) { toast("Cannot open"); }
        });
        TextView b2 = bold(tv("App info", 12, TEXT));
        b2.setPadding(dp(14), dp(8), dp(14), dp(8));
        b2.setBackground(bg(SURFACE2, 10, BORDER, 1));
        b2.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg)));
            } catch (Throwable e) { toast("Unavailable"); }
        });
        TextView b3 = bold(tv("Play Store", 12, TEXT));
        b3.setPadding(dp(14), dp(8), dp(14), dp(8));
        b3.setBackground(bg(SURFACE2, 10, BORDER, 1));
        b3.setOnClickListener(v -> openUrl("https://play.google.com/store/apps/details?id=" + pkg));
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        m1.rightMargin = dp(8);
        LinearLayout.LayoutParams m3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        m3.leftMargin = dp(8);
        btns.addView(b1, m1);
        btns.addView(b2);
        btns.addView(b3, m3);
        d.addView(btns, blp);
        return d;
    }
    private void loadInstalledKeys() {
        java.util.Set<String> set = new java.util.HashSet<>();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName != null) set.add(ai.packageName.toLowerCase());
                try { set.add(normKey(String.valueOf(ai.loadLabel(pm)))); } catch (Throwable e) {}
            }
        } catch (Throwable e) {}
        installedKeys = set;
    }
    private String normKey(String x) {
        return x == null ? "" : x.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
    private boolean isInstalledPkg(String pkg) {
        if (installedKeys == null) loadInstalledKeys();
        return pkg != null && !pkg.isEmpty() && installedKeys.contains(pkg.toLowerCase());
    }
    private boolean isInstalled(AppItem a) {
        if (isInstalledPkg(a.pkg)) return true;
        if (installedKeys == null) loadInstalledKeys();
        String k = normKey(a.name);
        return k.length() >= 4 && installedKeys.contains(k);
    }
    private boolean matchesInstalled(AppItem a, android.content.pm.ApplicationInfo ai) {
        if (a.pkg != null && !a.pkg.isEmpty() && a.pkg.equalsIgnoreCase(ai.packageName)) return true;
        String lbl = "";
        try { lbl = normKey(String.valueOf(ai.loadLabel(getPackageManager()))); } catch (Throwable e) {}
        return lbl.length() >= 4 && lbl.equals(normKey(a.name));
    }
    private boolean isGame(android.content.pm.ApplicationInfo ai) {
        for (AppItem a : repo.apps)
            if ("Gaming & Esports".equals(a.category) && matchesInstalled(a, ai)) return true;
        return false;
    }
    private TextView instBadge() {
        TextView b = bold(tv("Installed", 9.5f, 0xFF0B3D2E));
        b.setPadding(dp(6), dp(2), dp(6), dp(2));
        b.setBackground(bg(0xFFB9F6CA, 999, 0, 0));
        return b;
    }
    /** The one category that is deliberately not Indian, so it never gets a Swadeshi badge. */
    static final String NOT_INDIAN_CAT = "Play Store alternatives (not Indian)";

    private boolean showSwBadge(AppItem a) {
        return a == null || !NOT_INDIAN_CAT.equals(a.category);
    }

    private TextView swBadge() {
        TextView b = bold(tv("Swadeshi", 9.5f, 0xFF6DDCB0));
        b.setPadding(dp(6), dp(2), dp(6), dp(2));
        b.setBackground(bg(SURFACE2, 999, 0xFF2E7D4F, 1));
        return b;
    }
    private boolean isSwadeshi(android.content.pm.ApplicationInfo ai) {
        for (AppItem a : repo.apps) if (matchesInstalled(a, ai)) return true;
        return false;
    }
    private int installedCatalogCount() {
        int c = 0;
        for (AppItem a : repo.apps) if (isInstalled(a)) c++;
        return c;
    }
    private long[] storageStats() {
        long total = 1, free = 0;
        try {
            android.os.StatFs sf = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
            total = sf.getTotalBytes(); free = sf.getAvailableBytes();
        } catch (Throwable e) {}
        long used = total - free;
        long pct = total > 0 ? used * 100 / total : 0;
        return new long[]{used, total, pct};
    }
    private String fmtBytes(long b) {
        double gb = b / 1073741824.0;
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb);
        return String.format(Locale.US, "%.0f MB", b / 1048576.0);
    }
    private String ago(long t) {
        if (t <= 0) return "recently";
        long d = (System.currentTimeMillis() - t) / 86400000L;
        if (d <= 0) return "today";
        if (d == 1) return "1 day ago";
        if (d < 30) return d + " days ago";
        long m = d / 30;
        return m + (m == 1 ? " month ago" : " months ago");
    }

    // ---------------- illustrative size (demo) ----------------
    private int sizeOf(AppItem a) {
        int h = 0;
        for (char c : a.id.toCharArray()) h = h * 31 + c;
        return 8 + (Math.abs(h) % 175);
    }
    private String fmtSize(int mb) {
        return mb >= 1000 ? String.format(Locale.US, "%.1f GB", mb / 1000f) : mb + " MB";
    }
}
