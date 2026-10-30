package com.mihir.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.HashMap;
import java.util.Map;

/**
 * Split screen: the chosen HDMI input on the left, AntunesPlayer's last channel on the right.
 * Left / Right choose which side plays sound (white frame); Back closes it.
 */
public class SplitActivity extends Activity {
    private TvView tv;
    private VideoView vid;
    private MediaPlayer mp;
    private GradientDrawable leftRing, rightRing;
    private boolean soundRight;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String input = getIntent().getStringExtra("input");
        String label = getIntent().getStringExtra("label");
        SharedPreferences p = getSharedPreferences("bg", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout left = pane();
        tv = new TvView(this);
        left.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        leftRing = ring(left);
        left.addView(caption(label == null ? "HDMI" : label), new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));

        FrameLayout right = pane();
        vid = new VideoView(this);
        right.addView(vid, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        rightRing = ring(right);
        String name = p.getString("name", "");
        right.addView(caption(name.isEmpty() ? "AntunesPlayer" : name), new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(left, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(right, rp);

        TextView hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(0x99FFFFFF);
        hint.setText("Setas ◀ ▶ escolhem o som  ·  Voltar para sair");
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(28);
        root.addView(hint, hp);
        setContentView(root);

        if (input != null) tv.tune(input, TvContract.buildChannelUriForPassthroughInput(input));

        String url = p.getString("url", ""), ua = p.getString("ua", "");
        if (!url.isEmpty()) {
            Map<String, String> h = new HashMap<>();
            if (!ua.isEmpty()) h.put("User-Agent", ua);
            vid.setOnPreparedListener(m -> { mp = m; applySound(); vid.start(); });
            vid.setOnErrorListener((m, w, e) -> true);
            vid.setVideoURI(Uri.parse(url), h);
        }
        applySound();
    }

    private FrameLayout pane() {
        FrameLayout f = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
            }
        };
        f.setBackgroundColor(0xFF111111);
        f.setClipToOutline(true);
        return f;
    }

    private GradientDrawable ring(FrameLayout f) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(6));
        g.setStroke(dp(3), Color.TRANSPARENT);
        f.setForeground(g);
        return g;
    }

    private TextView caption(String s) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(dp(6), 0, 0, 0xCC000000);
        t.setPadding(dp(12), 0, dp(12), dp(10));
        t.setText(s);
        return t;
    }

    private void applySound() {
        leftRing.setStroke(dp(3), soundRight ? Color.TRANSPARENT : 0xE6FFFFFF);
        rightRing.setStroke(dp(3), soundRight ? 0xE6FFFFFF : Color.TRANSPARENT);
        try { tv.setStreamVolume(soundRight ? 0f : 1f); } catch (RuntimeException ignored) { }
        if (mp != null) try { float v = soundRight ? 1f : 0f; mp.setVolume(v, v); } catch (RuntimeException ignored) { }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            soundRight = code == KeyEvent.KEYCODE_DPAD_RIGHT;
            applySound();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { tv.reset(); } catch (RuntimeException ignored) { }
        try { vid.stopPlayback(); } catch (RuntimeException ignored) { }
        finish();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
