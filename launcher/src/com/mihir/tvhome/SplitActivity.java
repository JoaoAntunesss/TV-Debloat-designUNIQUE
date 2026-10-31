package com.mihir.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Split screen: the chosen HDMI input on the left (with its sound) and a small home screen on the right
 * (clock and the main apps, moved with the remote). OK opens an app; Back closes the split.
 */
public class SplitActivity extends Activity {
    private TvView tv;
    private TextView clock, date;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Locale pt = new Locale("pt", "PT");
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date d = new Date();
            clock.setText(new SimpleDateFormat("H:mm", pt).format(d));
            date.setText(new SimpleDateFormat("EEEE  d MMMM", pt).format(d).toUpperCase(pt));
            h.postDelayed(this, 20_000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        String input = getIntent().getStringExtra("input");
        String label = getIntent().getStringExtra("label");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView wall = new ImageView(this); // same photo as the home screen, darkened
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setAlpha(0.45f);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        loadWallpaper(wall);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), 0, dp(24), 0);
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout left = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int hh) {
                int width = MeasureSpec.getSize(w);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
            }
        };
        left.setBackgroundColor(0xFF111111);
        clip(left, dp(12));
        tv = new TvView(this);
        tv.setFocusable(false);
        left.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        TextView cap = text(12, Color.WHITE, "sans-serif-medium");
        cap.setText(label == null ? "HDMI" : label);
        cap.setShadowLayer(dp(6), 0, 0, 0xCC000000);
        cap.setPadding(dp(12), 0, 0, dp(10));
        left.addView(cap, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(0, -2, 1f);
        lp0.rightMargin = dp(8);
        row.addView(left, lp0);

        // same size as the HDMI picture: a 16:9 glass panel
        FrameLayout panel = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int hh) {
                int width = MeasureSpec.getSize(w);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
            }
        };
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x33000000);
        glass.setStroke(dp(1), 0x26FFFFFF);
        glass.setCornerRadius(dp(12));
        panel.setBackground(glass);
        panel.setClipChildren(false);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(16), dp(12), dp(16), dp(12));
        right.setClipChildren(false);
        panel.addView(right, new FrameLayout.LayoutParams(-1, -1));
        clock = text(30, Color.WHITE, "sans-serif-thin");
        clock.setIncludeFontPadding(false);
        date = text(10, 0x99FFFFFF, "sans-serif-medium");
        date.setLetterSpacing(0.22f);
        right.addView(clock);
        right.addView(date);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = dp(10);
        right.addView(grid, gp);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
        lp1.leftMargin = dp(8);
        row.addView(panel, lp1);
        row.setClipChildren(false);

        TextView hint = text(10, 0x99FFFFFF, null);
        hint.setText("OK abre a app  ·  Voltar fecha o ecrã dividido");
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(22);
        root.addView(hint, hp);
        setContentView(root);

        fillApps(grid);
        tick.run();
        if (input != null) tv.tune(input, TvContract.buildChannelUriForPassthroughInput(input));
    }

    private void fillApps(GridLayout grid) {
        PackageManager pm = getPackageManager();
        int panelW = (getResources().getDisplayMetrics().widthPixels - dp(48) - dp(16)) / 2;
        int w = (panelW - dp(32) - dp(16)) / 3;
        int i = 0;
        for (String pkg : getResources().getStringArray(R.array.dock_apps)) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> r = pm.queryIntentActivities(q, 0);
            if (r.isEmpty()) continue;
            ActivityInfo ai = r.get(0).activityInfo;
            Drawable art = ai.loadBanner(pm);
            if (art == null) art = ai.applicationInfo.loadBanner(pm);
            boolean banner = art != null;
            if (art == null) art = r.get(0).loadIcon(pm);
            FrameLayout card = new FrameLayout(this);
            card.setFocusable(true);
            card.setClickable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1C1C1E);
            bg.setCornerRadius(dp(8));
            card.setBackground(bg);
            clip(card, dp(8));
            ImageView img = new ImageView(this);
            img.setImageDrawable(art);
            img.setScaleType(banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            if (!banner) img.setPadding(dp(14), dp(8), dp(14), dp(8));
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(8));
            ring.setStroke(dp(2), Color.TRANSPARENT);
            card.setForeground(ring);
            card.setOnFocusChangeListener((v, has) -> {
                ring.setStroke(dp(2), has ? 0xE6FFFFFF : Color.TRANSPARENT);
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                        .setInterpolator(new OvershootInterpolator(1.8f)).setDuration(220).start();
            });
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            card.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                            .setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) { }
            });
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = w;
            p.height = w * 9 / 16;
            p.setMargins(i % 3 == 0 ? 0 : dp(8), 0, 0, dp(8));
            grid.addView(card, p);
            if (i == 0) card.post(card::requestFocus);
            i++;
        }
    }

    private void loadWallpaper(ImageView v) {
        new Thread(() -> {
            try {
                String[] all = getAssets().list("wallpapers");
                if (all == null || all.length == 0) return;
                int idx = Math.max(0, getSharedPreferences("MainActivity", MODE_PRIVATE).getInt("wallpaper", 0)) % all.length;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inSampleSize = 2;
                Bitmap bm;
                try (java.io.InputStream in = getAssets().open("wallpapers/" + all[idx])) { bm = BitmapFactory.decodeStream(in, null, o); }
                runOnUiThread(() -> v.setImageBitmap(bm));
            } catch (Exception ignored) { }
        }).start();
    }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (family != null) t.setTypeface(android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL));
        return t;
    }

    private void clip(View v, int radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View x, Outline o) { o.setRoundRect(0, 0, x.getWidth(), x.getHeight(), radius); }
        });
        v.setClipToOutline(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        h.removeCallbacks(tick);
        try { tv.reset(); } catch (RuntimeException ignored) { }
        finish();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
