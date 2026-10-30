package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.widget.VideoView;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        Runnable action;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- ambient (AntunesPlayer)
    // When the TV is switched on (a fresh boot, or waking from standby onto this home screen),
    // open AntunesPlayer in its ambient mode: last channel, full screen, no sound. Home comes back here.
    private long screenOnAt = -1;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_SCREEN_ON.equals(i.getAction())) return;
            screenOnAt = SystemClock.elapsedRealtime();
            if (resumed) maybeFire();

        }
    };
    private boolean resumed;
    private FrameLayout rootView;
    private boolean ambientBg;

    // See-through mode: no photo, so AntunesPlayer (playing muted behind this window) is the background.
    private void setAmbientBg(boolean on) {
        if (rootView == null || ambientBg == on) return;
        ambientBg = on;
        rootView.setBackgroundColor(on ? Color.TRANSPARENT : Color.BLACK);
        wallBack.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        wallFront.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        glow.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
    }

    private boolean freshBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        android.content.SharedPreferences p = getSharedPreferences("ambient", MODE_PRIVATE);
        if (boot < 0 || p.getInt("boot", -2) == boot) return false;
        p.edit().putInt("boot", boot).apply();
        return SystemClock.elapsedRealtime() < 10 * 60 * 1000;
    }

    private void maybeAmbient() {
        String pkg = getString(R.string.ambient_package);
        if (pkg.isEmpty() || !isInstalled(pkg)) return;
        boolean wake = screenOnAt > 0 && SystemClock.elapsedRealtime() - screenOnAt < 30_000;
        screenOnAt = -1;
        if (!freshBoot() && !wake) return;
        handler.postDelayed(() -> {
            if (!hasWindowFocus()) return;   // the user already opened something else
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.putExtra("ambient", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launch(i);
        }, 1200);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, sf);
        startMedia();
        AppServer.ensure(this);   // app list for the phone remote
        live = new java.lang.ref.WeakReference<>(this);
        handler.postDelayed(this::askScreenCapture, 3000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null && !last.isShown()) last = dockCards.isEmpty() ? null : dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
        resumed = true;
        startMedia();
        handler.removeCallbacks(startBgR);
        handler.postDelayed(startBgR, 800);
        maybeFire();
        handler.removeCallbacks(npTick);
        handler.post(npTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(startBgR);
        stopBg();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(npTick);
        if (panelPopup != null) panelPopup.dismiss();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(screen);
        if (msm != null) msm.removeOnActiveSessionsChangedListener(npSessions);
        if (npCtl != null) npCtl.unregisterCallback(npCb);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("ambient_bg", false)) setAmbientBg(true);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(Color.BLACK);
        // Live channel from AntunesPlayer, muted, under the photos; the photos fade out once it plays.
        bgVideo = new VideoView(this);
        bgVideo.setFocusable(false);
        bgVideo.setVisibility(View.GONE);
        bgVideo.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        root.addView(bgVideo, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));
        hero.addView(buildNowPlaying(), lp(-1, -2, Gravity.TOP | Gravity.START, dp(56), dp(84), dp(56), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("TODAS AS APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        compact = text(15, "sans-serif-light", Color.WHITE);
        compact.setSingleLine(true);
        compact.setVisibility(View.GONE);
        compact.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        left.addView(compact);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView store = pill("Loja", Icon.BAG);
        store.setOnClickListener(v -> openPackage("com.android.vending"));
        wifiPill = pill("Wi-Fi", Icon.WIFI);
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        panelBtn = pill("", Icon.PANEL);
        panelBtn.setOnClickListener(v -> showPanel(v));
        TextView settings = pill("", Icon.GEAR);
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (TextView p : new TextView[]{store, wifiPill, panelBtn, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, int icon) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(label);
        p.setGravity(Gravity.CENTER);
        boolean round = label.isEmpty();
        p.setPadding(round ? dp(8) : dp(12), 0, round ? dp(8) : dp(14), 0);
        Icon ic = new Icon(icon, 0xE6FFFFFF);
        ic.setBounds(0, 0, dp(14), dp(14));
        p.setCompoundDrawables(ic, null, null, null);
        p.setCompoundDrawablePadding(round ? 0 : dp(7));
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            ic.setColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        dock.add(actionApp("Aplicações", Icon.APPS, () -> { if (!gridCards.isEmpty()) gridCards.get(0).requestFocus(); }));
        dock.add(actionApp("Definições", Icon.GEAR, () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    private App actionApp(String label, int icon, Runnable action) {
        App a = new App();
        a.pkg = "";
        a.label = label;
        a.art = new Icon(icon, Color.WHITE);
        a.color = 0xFF7F8C93;
        a.action = action;
        return a;
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), app.action != null ? dp(12) : dp(14), dp(22), app.action != null ? dp(12) : dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        if (app.action != null) card.setOnClickListener(v -> app.action.run());
        else card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(npShown ? npColor : app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(npShown ? npPlay.getId() : d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(npShown ? npPlay.getId() : topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        if (npPrev != null) {
            View[] nb = {npPrev, npPlay, npNext, npOpen};
            int down = d > 0 ? dockCards.get(0).getId() : topId;
            for (int i = 0; i < nb.length; i++) {
                nb[i].setNextFocusLeftId(nb[Math.max(0, i - 1)].getId());
                nb[i].setNextFocusRightId(nb[Math.min(nb.length - 1, i + 1)].getId());
                nb[i].setNextFocusUpId(topId);
                nb[i].setNextFocusDownId(down);
            }
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(250), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        // Split screen: this HDMI on the left, the AntunesPlayer channel on the right.
        if (!getSharedPreferences("bg", MODE_PRIVATE).getString("url", "").isEmpty()) for (TvInputInfo t : inputs) {
            rows.add(inputRow("Ecrã dividido · " + inputLabel(t), () -> launch(new Intent(this, SplitActivity.class)
                    .putExtra("input", t.getId()).putExtra("label", inputLabel(t)))));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("TV em direto", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Definições", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "BOA NOITE" : h < 12 ? "BOM DIA" : h < 20 ? "BOA TARDE" : "BOA NOITE");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", new Locale("pt", "PT")).format(now.getTime()).toUpperCase(Locale.getDefault()));
        if (compact != null) {
            String dd = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "PT")).format(now.getTime());
            compact.setText(clock.getText() + "   |   " + Character.toUpperCase(dd.charAt(0)) + dd.substring(1));
        }
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Sem rede";
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText(name);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, root.indexOfChild(wallBack) + 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        stopBg();   // free the IPTV connection before the app opens
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            handler.removeCallbacks(idleStop);
            if (bgOn) handler.postDelayed(idleStop, BG_IDLE_MS);
            else if (bgIdle && resumed) startBg();
        }
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- now playing (Spotify and any app with a media session)

    private LinearLayout npCard, clockRow;
    private TextView compact, npTitle, npArtist, npTime, npLeft, npOpen, panelBtn;
    private ImageView npArt, npPrev, npPlay, npNext;
    private Icon npPlayIcon;
    private View npFill, npProg;
    private Bitmap npArtBmp;
    private int npColor = 0xFF3A3A3A;
    private boolean npShown, npListening;
    private MediaController npCtl;
    private MediaSessionManager msm;
    private Mini npMini;
    private PopupWindow panelPopup;

    static final class Mini { ImageView art; TextView title, artist; Icon play; View fill; }

    private final MediaController.Callback npCb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { updateNowPlaying(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { updateNowPlaying(); }
        @Override public void onSessionDestroyed() { pickSession(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener npSessions = l -> pickSession();
    private final Runnable npTick = new Runnable() {
        @Override public void run() {
            if (npShown || npMini != null) updateNowPlaying();
            handler.postDelayed(this, 1000);
        }
    };

    private ComponentName mediaListener() { return new ComponentName(this, MediaListener.class); }

    /** Needs notification access, granted over ADB: cmd notification allow_listener com.mihir.tvhome/.MediaListener */
    private void startMedia() {
        if (msm == null) msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (msm != null && !npListening) {
            try { msm.addOnActiveSessionsChangedListener(npSessions, mediaListener(), handler); npListening = true; }
            catch (SecurityException ignored) { }
        }
        pickSession();
    }

    private void pickSession() {
        if (msm == null) return;
        List<MediaController> list;
        try { list = msm.getActiveSessions(mediaListener()); } catch (SecurityException e) { list = Collections.emptyList(); }
        String skip = getString(R.string.ambient_package);
        MediaController best = null;
        for (MediaController m : list) {
            if (m.getPackageName().equals(skip)) continue;   // the muted TV channel behind the home screen
            if (best == null) best = m;
            PlaybackState s = m.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = m; break; }
        }
        boolean same = npCtl != null && best != null && npCtl.getSessionToken().equals(best.getSessionToken());
        if (!same) {
            if (npCtl != null) npCtl.unregisterCallback(npCb);
            if (best != null) best.registerCallback(npCb, handler);
            npCtl = best;
        }
        updateNowPlaying();
    }

    private void updateNowPlaying() {
        if (npCard == null) return;
        MediaController c = npCtl;
        MediaMetadata md = c == null ? null : c.getMetadata();
        PlaybackState st = c == null ? null : c.getPlaybackState();
        String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        int state = st == null ? PlaybackState.STATE_NONE : st.getState();
        boolean show = t != null && !t.isEmpty() && state != PlaybackState.STATE_NONE
                && state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR;
        if (show != npShown) {
            npShown = show;
            boolean hadFocus = npCard.hasFocus();
            npCard.setVisibility(show ? View.VISIBLE : View.GONE);
            compact.setVisibility(show ? View.VISIBLE : View.GONE);
            for (View v : new View[]{greeting, clockRow, date}) v.setVisibility(show ? View.GONE : View.VISIBLE);
            wireFocus();
            if (hadFocus && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
            if (show) {
                npCard.setAlpha(0f);
                npCard.setTranslationY(dp(14));
                npCard.animate().alpha(1f).translationY(0).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
            } else npArtBmp = null;
        }
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        Bitmap art = null;
        String artist = "";
        long dur = 0, pos = 0;
        if (show) {
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist == null) artist = appLabel(c.getPackageName());
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            pos = st.getPosition();
            if (playing && st.getLastPositionUpdateTime() > 0)
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            if (dur > 0) pos = Math.max(0, Math.min(pos, dur));
            npTitle.setText(t);
            npArtist.setText(artist);
            npPlayIcon.setType(playing ? Icon.PAUSE : Icon.PLAY);
            if (art != npArtBmp || npArt.getDrawable() == null) {
                npArtBmp = art;
                if (art != null) npArt.setImageBitmap(art); else npArt.setImageDrawable(appIcon(c.getPackageName()));
                npColor = art != null ? vivid(art) : 0xFF1DB954;
                animateGlow(npColor);
            }
            npProg.setVisibility(dur > 0 ? View.VISIBLE : View.INVISIBLE);
            if (dur > 0) {
                npFill.setPivotX(0f);
                npFill.setScaleX(pos / (float) dur);
                npTime.setText(fmt(pos));
                npLeft.setText("-" + fmt(dur - pos));
            }
        }
        Mini m = npMini;
        if (m != null) {
            m.title.setText(show ? t : "Nada a tocar");
            m.artist.setText(show ? artist : "Põe música no Spotify");
            m.play.setType(playing ? Icon.PAUSE : Icon.PLAY);
            if (show && art != null) m.art.setImageBitmap(art);
            else if (m.art.getDrawable() == null) m.art.setImageDrawable(new Icon(Icon.HEAD, 0x99FFFFFF));
            m.fill.setPivotX(0f);
            m.fill.setScaleX(show && dur > 0 ? pos / (float) dur : 0f);
        }
    }

    private void media(int what) {
        MediaController c = npCtl;
        if (c == null) return;
        MediaController.TransportControls tc = c.getTransportControls();
        PlaybackState st = c.getPlaybackState();
        if (what < 0) tc.skipToPrevious();
        else if (what > 0) tc.skipToNext();
        else if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause();
        else tc.play();
    }

    private void openNowPlaying() {
        MediaController c = npCtl;
        if (c == null) return;
        PendingIntent pi = c.getSessionActivity();
        try { if (pi != null) { pi.send(); return; } } catch (PendingIntent.CanceledException ignored) { }
        openPackage(c.getPackageName());
    }

    private String appLabel(String pkg) {
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (PackageManager.NameNotFoundException e) { return ""; }
    }

    private Drawable appIcon(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); }
        catch (PackageManager.NameNotFoundException e) { return new Icon(Icon.HEAD, Color.WHITE); }
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** Average colour of the album art, made brighter and more saturated for the glow. */
    private static int vivid(Bitmap b) {
        try {
            int c = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0);
            float[] h = new float[3];
            Color.colorToHSV(c, h);
            h[1] = Math.min(1f, h[1] * 1.4f);
            h[2] = Math.max(0.55f, Math.min(0.9f, h[2] * 1.3f));
            return Color.HSVToColor(h);
        } catch (RuntimeException e) {
            return 0xFF1DB954;
        }
    }

    private View buildNowPlaying() {
        npCard = vlay();
        npCard.setVisibility(View.GONE);
        LinearLayout top = hlay();
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        npArt.setClipToOutline(true);
        npArt.setElevation(dp(10));
        top.addView(npArt, new LinearLayout.LayoutParams(dp(118), dp(118)));

        LinearLayout info = vlay();
        npTitle = text(30, "sans-serif-medium", Color.WHITE);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        npTitle.setShadowLayer(dp(12), 0, 0, 0x66000000);
        info.addView(npTitle, new LinearLayout.LayoutParams(-1, -2));
        npArtist = text(13, "sans-serif", 0xCCFFFFFF);
        npArtist.setSingleLine(true);
        npArtist.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(npArtist, llp(-1, -2, 0, dp(2), 0, dp(12)));
        LinearLayout btns = hlay();
        npPrev = roundBtn(Icon.PREV, 34, () -> media(-1));
        npPlay = roundBtn(Icon.PAUSE, 34, () -> media(0));
        npPlayIcon = (Icon) npPlay.getDrawable();
        npNext = roundBtn(Icon.NEXT, 34, () -> media(1));
        for (View b : new View[]{npPrev, npPlay, npNext}) btns.addView(b, llp(dp(34), dp(34), 0, 0, dp(10), 0));
        npOpen = pill("Ouvir", Icon.HEAD);
        npOpen.setOnClickListener(v -> openNowPlaying());
        btns.addView(npOpen, llp(-2, dp(30), dp(6), 0, 0, 0));
        info.addView(btns);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.setMargins(dp(24), 0, 0, 0);
        top.addView(info, ip);
        npCard.addView(top);

        LinearLayout prog = vlay();
        npProg = prog;
        LinearLayout times = hlay();
        npTime = text(9, "sans-serif-medium", 0x99FFFFFF);
        npLeft = text(9, "sans-serif-medium", 0x99FFFFFF);
        times.addView(npTime, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(npLeft);
        prog.addView(times, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout bar = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(2));
        track.setColor(0x33FFFFFF);
        bar.setBackground(track);
        npFill = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(2));
        fill.setColor(0xE6FFFFFF);
        npFill.setBackground(fill);
        bar.addView(npFill, new FrameLayout.LayoutParams(-1, -1));
        prog.addView(bar, llp(-1, dp(3), 0, dp(5), 0, 0));
        npCard.addView(prog, llp(-1, -2, 0, dp(18), 0, 0));
        return npCard;
    }

    // ---------------------------------------------------------------- control panel

    private void showPanel(View anchor) {
        FrameLayout holder = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0xEB2B3038);
        bg.setStroke(dp(1), 0x2EFFFFFF);
        holder.setBackground(bg);
        holder.setPadding(dp(12), dp(12), dp(12), dp(10));
        PopupWindow pw = new PopupWindow(holder, dp(318), -2, true);
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(24));
        pw.setAnimationStyle(android.R.style.Animation_Dialog);
        pw.setOnDismissListener(() -> { panelPopup = null; npMini = null; anchor.requestFocus(); });
        panelPopup = pw;
        panelMain(holder, pw);
        pw.showAsDropDown(anchor, 0, dp(10), Gravity.END);
    }

    private void panelMain(FrameLayout holder, PopupWindow pw) {
        holder.removeAllViews();
        LinearLayout col = vlay();

        // Music + Wi-Fi + Sound
        LinearLayout r1 = hlay();
        LinearLayout music = vlay();
        music.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable mb = new GradientDrawable();
        mb.setCornerRadius(dp(18));
        mb.setColor(0x29FFFFFF);
        music.setBackground(mb);
        Mini m = new Mini();
        LinearLayout mt = hlay();
        m.art = new ImageView(this);
        m.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        m.art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(7)); }
        });
        m.art.setClipToOutline(true);
        mt.addView(m.art, new LinearLayout.LayoutParams(dp(34), dp(34)));
        ImageView pv = roundBtn(Icon.PREV, 24, () -> media(-1));
        ImageView pp = roundBtn(Icon.PLAY, 24, () -> media(0));
        ImageView nx = roundBtn(Icon.NEXT, 24, () -> media(1));
        m.play = (Icon) pp.getDrawable();
        for (View b : new View[]{pv, pp, nx}) mt.addView(b, llp(dp(24), dp(24), dp(5), 0, 0, 0));
        music.addView(mt);
        m.title = text(11, "sans-serif-medium", Color.WHITE);
        m.title.setSingleLine(true);
        m.title.setEllipsize(TextUtils.TruncateAt.END);
        music.addView(m.title, llp(-1, -2, 0, dp(10), 0, 0));
        m.artist = text(9, "sans-serif", 0xB3FFFFFF);
        m.artist.setSingleLine(true);
        m.artist.setEllipsize(TextUtils.TruncateAt.END);
        music.addView(m.artist, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout bar = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(2));
        track.setColor(0x33FFFFFF);
        bar.setBackground(track);
        m.fill = new View(this);
        m.fill.setBackgroundColor(0xE6FFFFFF);
        bar.addView(m.fill, new FrameLayout.LayoutParams(-1, -1));
        music.addView(bar, llp(-1, dp(3), 0, dp(10), 0, 0));
        r1.addView(music, wlp(-1, dp(8)));

        LinearLayout c1 = vlay();
        LinearLayout wifi = tile(Icon.WIFI, "Wi-Fi", wifiName(), () -> { pw.dismiss(); launch(new Intent(Settings.ACTION_WIFI_SETTINGS)); });
        c1.addView(wifi, llp(-1, dp(56), 0, 0, 0, dp(8)));
        c1.addView(tile(Icon.SPEAKER, "Som", audioOut(), () -> panelAudio(holder, pw)), llp(-1, dp(56), 0, 0, 0, 0));
        r1.addView(c1, wlp(-2, 0));
        col.addView(r1, new LinearLayout.LayoutParams(-1, -2));

        // Clock + Wallpaper
        LinearLayout r2 = hlay();
        r2.addView(tile(Icon.CLOCK, "Relógio", "Mostrar agora", () -> { pw.dismiss(); showClockSaver(); }), wlp(-1, dp(8)));
        LinearLayout wt = tile(Icon.IMAGE, "Fundo", bgModeLabel(), null);
        wt.setOnClickListener(v -> { nextBgMode(); ((TextView) wt.getTag()).setText(bgModeLabel()); });
        r2.addView(wt, wlp(-1, 0));
        col.addView(r2, llp(-1, dp(52), 0, dp(8), 0, 0));

        // Round shortcuts
        LinearLayout r3 = hlay();
        r3.setGravity(Gravity.CENTER);
        View[] round = {
            roundBtn(Icon.FIRE, 40, () -> { pw.dismiss(); openFire(); }),
            roundBtn(Icon.SEARCH, 40, () -> { pw.dismiss(); if (!launch(new Intent(Intent.ACTION_ASSIST))) launch(new Intent("android.search.action.GLOBAL_SEARCH")); }),
            roundBtn(Icon.ACCESS, 40, () -> { pw.dismiss(); launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }),
            roundBtn(Icon.SCREEN, 40, () -> { pw.dismiss(); if (!launch(new Intent(Settings.ACTION_DISPLAY_SETTINGS))) launch(new Intent(Settings.ACTION_SETTINGS)); }),
            roundBtn(Icon.INPUT, 40, () -> { pw.dismiss(); handler.post(() -> showInputs(panelBtn)); }),
            roundBtn(Icon.GEAR, 40, () -> { pw.dismiss(); launch(new Intent(Settings.ACTION_SETTINGS)); }),
        };
        for (View b : round) r3.addView(b, llp(dp(40), dp(40), dp(4), 0, dp(4), 0));
        col.addView(r3, llp(-1, -2, 0, dp(14), 0, 0));

        TextView foot = text(9, "sans-serif-medium", 0x80FFFFFF);
        foot.setGravity(Gravity.CENTER);
        foot.setText(memLine());
        col.addView(foot, llp(-1, -2, 0, dp(12), 0, 0));

        holder.addView(col, new FrameLayout.LayoutParams(-1, -2));
        npMini = m;
        updateNowPlaying();
        wifi.requestFocus();
    }

    private void panelAudio(FrameLayout holder, PopupWindow pw) {
        holder.removeAllViews();
        npMini = null;
        LinearLayout col = vlay();
        TextView h = text(11, "sans-serif-medium", 0xB3FFFFFF);
        h.setText("O som sai por");
        col.addView(h, llp(-2, -2, dp(6), dp(2), 0, dp(10)));
        String bt = btOutput();
        Runnable btSettings = () -> { pw.dismiss(); launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); };
        List<View> rows = new ArrayList<>();
        rows.add(tile(Icon.SPEAKER, "Colunas da TV", bt == null ? "Em uso" : "Disponível",
                bt == null ? () -> panelMain(holder, pw) : btSettings));
        for (String[] d : btAudioDevices(bt)) rows.add(tile(Icon.HEAD, d[0], d[1], btSettings));
        rows.add(tile(Icon.BT, "Emparelhar auscultadores", "Definições de Bluetooth", btSettings));
        rows.add(tile(Icon.BACK, "Voltar", "", () -> panelMain(holder, pw)));
        for (View r : rows) col.addView(r, llp(-1, dp(52), 0, 0, 0, dp(8)));
        holder.addView(col, new FrameLayout.LayoutParams(-1, -2));
        rows.get(0).requestFocus();
    }

    private String btOutput() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET) return String.valueOf(d.getProductName());
        }
        return null;
    }

    private String audioOut() {
        String b = btOutput();
        return b != null ? b : "Colunas da TV";
    }

    private List<String[]> btAudioDevices(String active) {
        List<String[]> out = new ArrayList<>();
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) return out;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                BluetoothClass k = d.getBluetoothClass();
                if (k == null || k.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
                String n = d.getName();
                if (n == null) continue;
                out.add(new String[]{n, n.equals(active) ? "Em uso" : "Não ligado"});
            }
        } catch (SecurityException ignored) { }
        return out;
    }

    private String wifiName() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Sem rede";
            }
        } catch (SecurityException ignored) { }
        return name;
    }

    private String wallLabel() {
        return wallpapers.length == 0 ? "" : "Foto " + (Math.max(0, wallIndex) + 1) + " de " + wallpapers.length;
    }

    private String memLine() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
        return String.format(new Locale("pt", "PT"), "%.1f GB de RAM livre  ·  %.1f GB de espaço livre",
                mi.availMem / 1e9, sf.getAvailableBytes() / 1e9);
    }

    /** Full-screen clock that drifts slowly (no burn-in). Any key closes it. */
    private void showClockSaver() {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Color.BLACK);
        ImageView w = new ImageView(this);
        w.setScaleType(ImageView.ScaleType.CENTER_CROP);
        w.setImageDrawable(wallFront.getDrawable());
        w.setAlpha(0.3f);
        f.addView(w, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout l = vlay();
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView sub = text(16, "sans-serif-light", 0xCCFFFFFF);
        sub.setLetterSpacing(0.15f);
        TextView big = text(110, "sans-serif-thin", Color.WHITE);
        big.setIncludeFontPadding(false);
        l.addView(sub);
        l.addView(big);
        f.addView(l, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        Locale pt = new Locale("pt", "PT");
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            Date now = new Date();
            big.setText(new SimpleDateFormat("H:mm", pt).format(now));
            sub.setText(new SimpleDateFormat("EEEE, d 'de' MMMM", pt).format(now).toUpperCase(pt));
            l.animate().translationX((float) (Math.random() - 0.5) * dp(160))
                    .translationY((float) (Math.random() - 0.5) * dp(90)).setDuration(4000).start();
            big.postDelayed(tick[0], 30_000);
        };
        tick[0].run();
        d.setContentView(f);
        d.setOnKeyListener((di, k, e) -> { if (e.getAction() == KeyEvent.ACTION_UP) di.dismiss(); return true; });
        d.setOnDismissListener(x -> big.removeCallbacks(tick[0]));
        d.show();
    }

    // ---------------------------------------------------------------- small widgets

    private LinearLayout tile(int icon, String title, String sub, Runnable act) {
        LinearLayout t = hlay();
        t.setPadding(dp(9), 0, dp(10), 0);
        t.setFocusable(true);
        t.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x29FFFFFF);
        t.setBackground(bg);
        ImageView iv = new ImageView(this);
        Icon ic = new Icon(icon, Color.WHITE);
        iv.setImageDrawable(ic);
        GradientDrawable ib = new GradientDrawable();
        ib.setShape(GradientDrawable.OVAL);
        ib.setColor(0x33FFFFFF);
        iv.setBackground(ib);
        iv.setPadding(dp(6), dp(6), dp(6), dp(6));
        t.addView(iv, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout tx = vlay();
        TextView a = text(11, "sans-serif-medium", Color.WHITE);
        a.setText(title);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        TextView b = text(9, "sans-serif", 0xB3FFFFFF);
        b.setText(sub);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        tx.addView(a);
        tx.addView(b);
        t.addView(tx, llp(-1, -2, dp(8), 0, 0, 0));
        t.setTag(b);
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x29FFFFFF);
            a.setTextColor(has ? 0xFF111111 : Color.WHITE);
            b.setTextColor(has ? 0x99111111 : 0xB3FFFFFF);
            ib.setColor(has ? 0xFF2D7FF9 : 0x33FFFFFF);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(160).start();
        });
        if (act != null) t.setOnClickListener(v -> act.run());
        return t;
    }

    private ImageView roundBtn(int icon, int sizeDp, Runnable act) {
        ImageView v = new ImageView(this);
        Icon ic = new Icon(icon, 0xF2FFFFFF);
        v.setImageDrawable(ic);
        int pad = dp(sizeDp * 0.28f);
        v.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
        bg.setStroke(dp(1), 0x26FFFFFF);
        v.setBackground(bg);
        v.setFocusable(true);
        v.setId(View.generateViewId());
        v.setOnFocusChangeListener((x, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x33FFFFFF);
            ic.setColor(has ? 0xFF111111 : 0xF2FFFFFF);
            x.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(200).start();
            if (has && x.getRootView() == scroll.getRootView()) { lastFocusedId = x.getId(); scroll.smoothScrollTo(0, 0); }
        });
        v.setOnClickListener(x -> act.run());
        return v;
    }

    private LinearLayout vlay() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private LinearLayout hlay() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private LinearLayout.LayoutParams wlp(int h, int marginRight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1f);
        p.setMargins(0, 0, marginRight, 0);
        return p;
    }

    /** Line icons drawn on a 24x24 grid, so they look the same on every TV (no emoji fonts). */
    static final class Icon extends Drawable {
        static final int PREV = 0, PLAY = 1, PAUSE = 2, NEXT = 3, SEARCH = 4, ACCESS = 5, SCREEN = 6, INPUT = 7, HEAD = 8,
                SPEAKER = 9, BT = 10, CLOCK = 11, IMAGE = 12, WIFI = 13, PANEL = 14, BAG = 15, BACK = 16, GEAR = 17, APPS = 18, FIRE = 19;
        private int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        Icon(int type, int color) {
            this.type = type;
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setType(int t) { if (t != type) { type = t; invalidateSelf(); } }
        void setColor(int c) { p.setColor(c); invalidateSelf(); }
        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }

        private void fill() { p.setStyle(Paint.Style.FILL); }
        private void stroke(float w) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); }
        private void poly(float... xy) {
            path.reset();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        }
        private void rr(Canvas c, float l, float t, float rt, float b, float rad) { r.set(l, t, rt, b); c.drawRoundRect(r, rad, rad, p); }
        private void arc(Canvas c, float cx, float cy, float rad, float start, float sweep) {
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            c.drawArc(r, start, sweep, false, p);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            c.save();
            c.translate(b.exactCenterX() - s / 2f, b.exactCenterY() - s / 2f);
            c.scale(s / 24f, s / 24f);
            switch (type) {
                case PLAY: fill(); poly(8, 5, 19, 12, 8, 19); path.close(); c.drawPath(path, p); break;
                case PAUSE: fill(); rr(c, 6.5f, 5, 10.5f, 19, 1.2f); rr(c, 13.5f, 5, 17.5f, 19, 1.2f); break;
                case PREV: fill(); rr(c, 5, 6, 7.5f, 18, 1); poly(19, 6, 9, 12, 19, 18); path.close(); c.drawPath(path, p); break;
                case NEXT: fill(); rr(c, 16.5f, 6, 19, 18, 1); poly(5, 6, 15, 12, 5, 18); path.close(); c.drawPath(path, p); break;
                case SEARCH: stroke(2.2f); c.drawCircle(10.5f, 10.5f, 6, p); c.drawLine(15, 15, 20, 20, p); break;
                case ACCESS:
                    fill(); c.drawCircle(12, 4.5f, 2, p);
                    stroke(2f); c.drawLine(5, 9, 19, 9, p); c.drawLine(12, 9, 12, 14, p);
                    c.drawLine(12, 14, 8.5f, 20.5f, p); c.drawLine(12, 14, 15.5f, 20.5f, p); break;
                case SCREEN: stroke(2f); rr(c, 3, 5, 21, 16, 2); c.drawLine(8, 20, 16, 20, p); break;
                case INPUT: stroke(2f); rr(c, 3, 5, 21, 19, 2.5f); c.drawLine(8, 12, 16, 12, p); poly(13, 8.5f, 16.5f, 12, 13, 15.5f); c.drawPath(path, p); break;
                case HEAD: stroke(2f); arc(c, 12, 13, 8, 180, 180); fill(); rr(c, 3, 12.5f, 7.5f, 20, 1.8f); rr(c, 16.5f, 12.5f, 21, 20, 1.8f); break;
                case SPEAKER:
                    fill(); poly(3.5f, 9, 7.5f, 9, 12.5f, 4.5f, 12.5f, 19.5f, 7.5f, 15, 3.5f, 15); path.close(); c.drawPath(path, p);
                    stroke(2f); arc(c, 13, 12, 4, -50, 100); arc(c, 13, 12, 8, -50, 100); break;
                case BT: stroke(2f); poly(6.5f, 7.5f, 17, 16, 12, 20.5f, 12, 3.5f, 17, 8, 6.5f, 16.5f); c.drawPath(path, p); break;
                case CLOCK: stroke(2f); c.drawCircle(12, 12, 8.5f, p); c.drawLine(12, 7, 12, 12, p); c.drawLine(12, 12, 15.5f, 14, p); break;
                case IMAGE:
                    stroke(2f); rr(c, 3, 5, 21, 19, 2.5f); poly(5.5f, 16.5f, 10, 11.5f, 13.5f, 15, 15.5f, 13, 18.5f, 16.5f); c.drawPath(path, p);
                    fill(); c.drawCircle(16, 9, 1.6f, p); break;
                case WIFI:
                    stroke(2.2f); arc(c, 12, 19, 4.5f, 225, 90); arc(c, 12, 19, 9, 225, 90); arc(c, 12, 19, 13.5f, 225, 90);
                    fill(); c.drawCircle(12, 19, 1.5f, p); break;
                case PANEL:
                    stroke(2f); rr(c, 3.5f, 4.5f, 20.5f, 11, 3.25f); rr(c, 3.5f, 13, 20.5f, 19.5f, 3.25f);
                    fill(); c.drawCircle(17.2f, 7.75f, 1.9f, p); c.drawCircle(6.8f, 16.25f, 1.9f, p); break;
                case BAG: stroke(2f); rr(c, 4, 8, 20, 20.5f, 2.5f); arc(c, 12, 8, 3.8f, 180, 180); break;
                case BACK: stroke(2.4f); poly(14.5f, 5, 8, 12, 14.5f, 19); c.drawPath(path, p); break;
                case GEAR:
                    stroke(2f); c.drawCircle(12, 12, 3, p); c.drawCircle(12, 12, 6.8f, p);
                    stroke(3.2f);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        c.drawLine(12 + (float) Math.cos(a) * 7.6f, 12 + (float) Math.sin(a) * 7.6f,
                                12 + (float) Math.cos(a) * 9.6f, 12 + (float) Math.sin(a) * 9.6f, p);
                    }
                    break;
                case FIRE:
                    stroke(2f);
                    path.reset();
                    path.moveTo(12, 2.5f);
                    path.cubicTo(14.5f, 7, 18.5f, 9.5f, 18.5f, 14.5f);
                    path.cubicTo(18.5f, 18.5f, 15.5f, 21.5f, 12, 21.5f);
                    path.cubicTo(8.5f, 21.5f, 5.5f, 18.5f, 5.5f, 15);
                    path.cubicTo(5.5f, 12, 7.5f, 10.5f, 8.5f, 8.5f);
                    path.cubicTo(9, 10.5f, 10, 11.5f, 11.2f, 12);
                    path.cubicTo(10.6f, 8.5f, 10.8f, 5.5f, 12, 2.5f);
                    path.close();
                    c.drawPath(path, p);
                    break;
                case APPS:
                    fill();
                    for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) rr(c, 4 + i * 6, 4 + j * 6, 8 + i * 6, 8 + j * 6, 1.2f);
                    break;
            }
            c.restore();
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- live background (AntunesPlayer's last channel, muted)

    private VideoView bgVideo;
    private boolean bgOn, bgAlt;
    private final Runnable startBgR = this::startBg;
    private boolean bgIdle;

    static final String FIRE_URL = "https://www.youtube.com/watch?v=B5nZdL6-AeA";
    static final String FIRE_APP = "org.smarttube.stable";

    private String bgMode() { return getSharedPreferences("bg", MODE_PRIVATE).getString("mode", "canal"); }

    private String bgModeLabel() {
        switch (bgMode()) {
            case "lareira": return "Lareira (com som)";
            case "fotos": return "Fotos da natureza";
            default: return "Canal AntunesPlayer";
        }
    }

    private void nextBgMode() {
        String m = bgMode();
        String n = m.equals("canal") ? "lareira" : m.equals("lareira") ? "fotos" : "canal";
        getSharedPreferences("bg", MODE_PRIVATE).edit().putString("mode", n).apply();
        stopBg();
        if (n.equals("canal")) startBg();
    }

    /** Fireplace video full screen with sound, in SmartTube (YouTube without ads). Back returns here. */
    private void openFire() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(FIRE_URL));
        if (isInstalled(FIRE_APP)) i.setPackage(FIRE_APP);
        launch(i);
    }

    /** Lareira theme: the fireplace starts by itself when the TV is switched on (fresh boot or wake from standby). */
    private void maybeFire() {
        boolean wake = screenOnAt > 0 && SystemClock.elapsedRealtime() - screenOnAt < 30_000;
        boolean boot = freshBoot();
        screenOnAt = -1;
        if (!bgMode().equals("lareira") || !(wake || boot)) return;
        handler.postDelayed(() -> { if (resumed && hasWindowFocus()) openFire(); }, 1500);
    }
    static final long BG_IDLE_MS = 30 * 60 * 1000;
    private final Runnable idleStop = () -> { stopBg(); bgIdle = true; };

    private void startBg() {
        if (bgOn || !resumed) return;
        android.content.SharedPreferences p = getSharedPreferences("bg", MODE_PRIVATE);
        String url = p.getString("url", "");
        if (!bgMode().equals("canal")) return;
        if (url.isEmpty() || !isInstalled(getString(R.string.ambient_package))) return;
        bgOn = true;
        bgAlt = false;
        bgIdle = false;
        handler.removeCallbacks(idleStop);
        handler.postDelayed(idleStop, BG_IDLE_MS);
        playBg(url, p.getString("ua", ""));
    }

    private void playBg(String url, String ua) {
        Map<String, String> h = new HashMap<>();
        if (!ua.isEmpty()) h.put("User-Agent", ua);
        bgVideo.setOnPreparedListener(mp -> {
            mp.setVolume(0f, 0f);
            if (bgOn) bgVideo.start();
        });
        bgVideo.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && bgOn) {
                for (View w : new View[]{wallBack, wallFront}) {
                    w.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                        if (bgOn) { w.setVisibility(View.INVISIBLE); w.setAlpha(1f); }
                    }).start();
                }
            }
            return false;
        });
        bgVideo.setOnErrorListener((mp, what, extra) -> {
            String alt = url.matches("(?i).*\\.m3u8(\\?.*)?$") ? url.replaceFirst("(?i)\\.m3u8", ".ts")
                    : url.matches("(?i).*\\.ts(\\?.*)?$") ? url.replaceFirst("(?i)\\.ts", ".m3u8") : null;
            if (bgOn && !bgAlt && alt != null) { bgAlt = true; handler.post(() -> playBg(alt, ua)); }
            else stopBg();
            return true;
        });
        bgVideo.setOnCompletionListener(mp -> handler.postDelayed(() -> { if (bgOn) playBg(url, ua); }, 2000));
        bgVideo.setVisibility(View.VISIBLE);
        bgVideo.setVideoURI(Uri.parse(url), h);
    }

    private void stopBg() {
        if (!bgOn) return;
        bgOn = false;
        try { bgVideo.stopPlayback(); } catch (RuntimeException ignored) { }
        bgVideo.setVisibility(View.GONE);
        for (View w : new View[]{wallBack, wallFront}) {
            w.animate().cancel();
            w.setAlpha(1f);
            w.setVisibility(View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------- phone remote (Comando TV), called from AppServer threads

    private static java.lang.ref.WeakReference<MainActivity> live = new java.lang.ref.WeakReference<>(null);

    private static <T> T onUi(java.util.concurrent.Callable<T> job, T fallback) {
        MainActivity a = live.get();
        if (a == null || a.isDestroyed()) return fallback;
        java.util.concurrent.atomic.AtomicReference<T> r = new java.util.concurrent.atomic.AtomicReference<>(fallback);
        java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        a.runOnUiThread(() -> { try { r.set(job.call()); } catch (Exception ignored) { } finally { l.countDown(); } });
        try { l.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return r.get();
    }

    /** What is on: the music playing (with its cover) or the live channel behind the home screen. */
    static String remoteNow() {
        return onUi(() -> {
            MainActivity a = live.get();
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("bg", a.bgMode());
            MediaController c = a.npCtl;
            MediaMetadata md = c == null ? null : c.getMetadata();
            String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (a.npShown && t != null) {
                String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                o.put("title", t);
                o.put("sub", (artist == null ? "" : artist + " · ") + a.appLabel(c.getPackageName()));
                o.put("pkg", c.getPackageName());
                if (a.npArtBmp != null) {
                    Bitmap s = Bitmap.createScaledBitmap(a.npArtBmp, 160, 160, true);
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    s.compress(Bitmap.CompressFormat.JPEG, 80, b);
                    o.put("img", android.util.Base64.encodeToString(b.toByteArray(), android.util.Base64.NO_WRAP));
                }
            } else if (a.bgOn) {
                String name = a.getSharedPreferences("bg", MODE_PRIVATE).getString("name", "");
                o.put("title", name.isEmpty() ? "Canal AntunesPlayer" : name);
                o.put("sub", "AntunesPlayer · fundo do ecrã inicial");
                o.put("pkg", a.getString(R.string.ambient_package));
            }
            return o.toString();
        }, "{}");
    }

    /** HDMI and other inputs of the TV. */
    static String remoteInputs(Context ctx) {
        org.json.JSONArray a = new org.json.JSONArray();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(TV_INPUT_SERVICE);
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (!t.isPassthroughInput() || t.isHidden(ctx)) continue;
            CharSequence l = t.loadCustomLabel(ctx);
            if (l == null || l.length() == 0) l = t.loadLabel(ctx);
            try { a.put(new org.json.JSONObject().put("id", t.getId()).put("label", String.valueOf(l))); } catch (org.json.JSONException ignored) { }
        }
        return a.toString();
    }

    /** Actions asked by the phone. The phone presses Home first, so the home screen is in front and may open things. */
    static boolean remoteDo(String action, String id) {
        if (action == null) return false;
        return onUi(() -> {
            MainActivity a = live.get();
            switch (action) {
                case "fire": a.openFire(); return true;
                case "clock": a.showClockSaver(); return true;
                case "apps": if (!a.gridCards.isEmpty()) a.gridCards.get(0).requestFocus(); return true;
                case "input":
                    if (id == null) return false;
                    Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (!a.launch(i)) a.openPackage(a.inputPickerPkg);
                    return true;
                case "split":
                    if (id == null) return false;
                    String label = "HDMI";
                    TvInputManager tim = (TvInputManager) a.getSystemService(TV_INPUT_SERVICE);
                    if (tim != null) for (TvInputInfo t : tim.getTvInputList()) if (t.getId().equals(id)) label = a.inputLabel(t);
                    a.launch(new Intent(a, SplitActivity.class).putExtra("input", id).putExtra("label", label));
                    return true;
            }
            return false;
        }, false);
    }

    /** Background theme of the home screen: canal, lareira or fotos. */
    static boolean remoteBg(Context ctx, String mode) {
        if (!"canal".equals(mode) && !"lareira".equals(mode) && !"fotos".equals(mode)) return false;
        ctx.getSharedPreferences("bg", MODE_PRIVATE).edit().putString("mode", mode).apply();
        onUi(() -> {
            MainActivity a = live.get();
            a.stopBg();
            if (mode.equals("canal") && a.resumed) a.startBg();
            return true;
        }, false);
        return true;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == 77 && result == RESULT_OK && data != null) ShotService.start(this, result, data);
    }

    /** Screen pictures for the phone. Pre-approved with appops PROJECT_MEDIA, so no question appears on the TV. */
    private void askScreenCapture() {
        if (ShotService.ready()) return;
        try {
            android.media.projection.MediaProjectionManager m = (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), 77);
        } catch (RuntimeException ignored) { }
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
