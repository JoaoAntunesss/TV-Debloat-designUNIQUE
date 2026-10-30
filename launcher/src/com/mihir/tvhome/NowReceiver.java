package com.mihir.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** AntunesPlayer tells us which live channel it is playing; the home screen shows it muted as its background. */
public class NowReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String url = i.getStringExtra("url");
        if (url == null || url.isEmpty()) return;
        String name = i.getStringExtra("name"), ua = i.getStringExtra("ua");
        c.getSharedPreferences("bg", Context.MODE_PRIVATE).edit()
                .putString("url", url)
                .putString("name", name == null ? "" : name)
                .putString("ua", ua == null ? "" : ua)
                .apply();
    }
}
