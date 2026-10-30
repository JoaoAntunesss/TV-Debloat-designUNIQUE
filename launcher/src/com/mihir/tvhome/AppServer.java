package com.mihir.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiny web service on the home Wi-Fi (port 8765) for the "Comando TV" phone app: GET /apps returns the
 * TV's apps (dock first) with their banners, so the phone can show them and open them.
 */
final class AppServer extends Thread {
    private static AppServer running;
    private final Context ctx;
    private String cache;
    private long cachedAt;

    private AppServer(Context c) {
        super("AppServer");
        ctx = c.getApplicationContext();
        setDaemon(true);
    }

    static synchronized void ensure(Context c) {
        if (running != null && running.isAlive()) return;
        running = new AppServer(c);
        running.start();
    }

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket(8765)) {
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> handle(s)).start();
            }
        } catch (Exception ignored) { }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            InputStream in = sock.getInputStream();
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') line.append((char) c);
            // read the rest of the request headers (closing with unread data resets the connection)
            int nl = 0;
            while (nl < 2 && (c = in.read()) >= 0) { if (c == '\n') nl++; else if (c != '\r') nl = 0; }
            String[] parts = line.toString().split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String route = path.split("\\?")[0];
            java.util.Map<String, String> q = new java.util.HashMap<>();
            if (path.contains("?")) for (String kv : path.substring(path.indexOf('?') + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) q.put(kv.substring(0, e), java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            }
            byte[] body = null;
            String type = "application/json; charset=utf-8";
            switch (route) {
                case "/apps": body = utf8(apps()); break;
                case "/shot.jpg": body = ShotService.frame(); type = "image/jpeg"; break;
                case "/now": body = utf8(MainActivity.remoteNow()); break;
                case "/inputs": body = utf8(MainActivity.remoteInputs(ctx)); break;
                case "/do": body = MainActivity.remoteDo(q.get("a"), q.get("id")) ? utf8("{\"ok\":true}") : null; break;
                case "/bg": body = MainActivity.remoteBg(ctx, q.get("mode")) ? utf8("{\"ok\":true}") : null; break;
                default: body = utf8("Comando TV"); type = "text/plain";
            }
            OutputStream out = sock.getOutputStream();
            if (body == null) {
                out.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } else {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                        + "\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
            }
            out.flush();
        } catch (Exception ignored) { }
    }

    private static byte[] utf8(String s) { return s == null ? null : s.getBytes(StandardCharsets.UTF_8); }

    private synchronized String apps() {
        if (cache != null && System.currentTimeMillis() - cachedAt < 60_000) return cache;
        PackageManager pm = ctx.getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(ctx.getPackageName());
        Map<String, ResolveInfo> byPkg = new LinkedHashMap<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (!hidden.contains(p) && !byPkg.containsKey(p)) byPkg.put(p, ri);
        }
        List<String> order = new ArrayList<>();
        for (String p : ctx.getResources().getStringArray(R.array.dock_apps)) if (byPkg.containsKey(p)) order.add(p);
        List<String> rest = new ArrayList<>(byPkg.keySet());
        rest.removeAll(order);
        Collections.sort(rest, (a, b) -> label(pm, byPkg.get(a)).compareToIgnoreCase(label(pm, byPkg.get(b))));
        order.addAll(rest);
        StringBuilder sb = new StringBuilder("[");
        for (String p : order) {
            ResolveInfo ri = byPkg.get(p);
            ActivityInfo ai = ri.activityInfo;
            Drawable d = ai.loadBanner(pm);
            if (d == null) d = ai.applicationInfo.loadBanner(pm);
            boolean banner = d != null;
            if (d == null) d = ri.loadIcon(pm);
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"pkg\":\"").append(esc(p)).append("\",\"label\":\"").append(esc(label(pm, ri)))
                    .append("\",\"img\":\"").append(jpeg(d, banner)).append("\"}");
        }
        cache = sb.append(']').toString();
        cachedAt = System.currentTimeMillis();
        return cache;
    }

    private static String label(PackageManager pm, ResolveInfo ri) { return String.valueOf(ri.loadLabel(pm)); }

    private static String jpeg(Drawable d, boolean banner) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF1B1D23);
        if (banner) d.setBounds(0, 0, w, h);
        else { int s = 110; d.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2); }
        try { d.draw(c); } catch (RuntimeException ignored) { }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 82, o);
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(' ');
            else b.append(ch);
        }
        return b.toString();
    }
}
