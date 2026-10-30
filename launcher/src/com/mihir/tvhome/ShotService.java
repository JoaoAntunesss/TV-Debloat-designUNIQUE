package com.mihir.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Pictures of the TV screen for the phone remote ("Ver aqui"). Allowed once at install with
 * appops PROJECT_MEDIA, so the TV never asks. The capture only runs while the phone is asking for
 * pictures and stops by itself 15 s later. Protected video (Netflix, HDMI...) comes out black.
 */
public class ShotService extends Service {
    static final int W = 960, H = 540;
    private static MediaProjection projection;
    private static VirtualDisplay display;
    private static ImageReader reader;
    private static Handler worker;
    private static volatile byte[] lastJpeg;
    private static volatile long lastAsk;
    private static final Runnable idle = ShotService::idleCheck;

    static boolean ready() { return projection != null; }

    static void start(Context c, int code, Intent data) {
        Intent i = new Intent(c, ShotService.class).putExtra("code", code).putExtra("data", data);
        c.startForegroundService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("shot", "Ecrã para o comando", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "shot").setContentTitle("Comando TV").setSmallIcon(R.drawable.ic_home).build();
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (intent != null && projection == null) {
            Intent data = intent.getParcelableExtra("data");
            int code = intent.getIntExtra("code", 0);
            if (data != null) {
                MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
                try { projection = m.getMediaProjection(code, data); } catch (RuntimeException ignored) { }
                if (projection != null) projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { release(); projection = null; }
                }, null);
            }
        }
        if (worker == null) {
            HandlerThread t = new HandlerThread("shot");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return START_STICKY;
    }

    /** Latest picture of the screen as JPEG, or null if not available. */
    static synchronized byte[] frame() {
        if (projection == null || worker == null) return null;
        lastAsk = System.currentTimeMillis();
        if (display == null) {
            reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> encode(), worker);
            display = projection.createVirtualDisplay("comando", W, H, 160,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
            worker.removeCallbacks(idle);
            worker.postDelayed(idle, 15_000);
            for (int i = 0; i < 20 && lastJpeg == null; i++) try { Thread.sleep(50); } catch (InterruptedException ignored) { }
        }
        return lastJpeg;
    }

    private static long lastEncode;

    private static void encode() {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;
            long now = System.currentTimeMillis();
            if (now - lastEncode < 150) return; // about 6 pictures per second is plenty
            lastEncode = now;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int stride = p.getRowStride() / p.getPixelStride();
            Bitmap bm = Bitmap.createBitmap(stride, H, Bitmap.Config.ARGB_8888);
            bm.copyPixelsFromBuffer(buf);
            Bitmap crop = stride == W ? bm : Bitmap.createBitmap(bm, 0, 0, W, H);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            crop.compress(Bitmap.CompressFormat.JPEG, 70, o);
            lastJpeg = o.toByteArray();
        } catch (RuntimeException ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private static synchronized void idleCheck() {
        if (System.currentTimeMillis() - lastAsk >= 15_000) release();
        else worker.postDelayed(idle, 5_000);
    }

    private static synchronized void release() {
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        lastJpeg = null;
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
