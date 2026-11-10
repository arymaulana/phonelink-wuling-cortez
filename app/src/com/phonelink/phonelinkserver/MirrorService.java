package com.phonelink.phonelinkserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;

/** Talks to the headunit over USB and streams the phone screen to it. */
public class MirrorService extends Service {
    static final String EXTRA_RESULT = "result";
    static final String EXTRA_DATA = "data";
    static volatile boolean capturing;
    static volatile boolean connected;

    private static final String TAG = "PLINK";
    static final String ACTION_STOP = "com.phonelink.phonelinkserver.action.STOP";
    private static final int NOTIFICATION_ID = 1;
    private static final long IDLE_FRAME_MS = 100;
    private static final long POLL_MS = 15;
    private static final long FIRST_CONNECT_MS = 10 * 60 * 1000; // headunit starts the app before USB connects
    private static final long UNPLUG_GRACE_MS = 15_000; // a loose cable or engine start needs no new prompt
    private static final long ACCESSORY_GONE_MS = 10_000;
    // How much taller than true shape. 100 = no stretch, 134 fills a 1080x2460 phone. Set by eye in the car.
    private static final int STRETCH_PERCENT = 117;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FileWriter logFile;
    private volatile FileOutputStream out;
    private volatile boolean sending;
    private volatile boolean displayKnown;
    private volatile boolean wholeScreen = true;
    private volatile int width = 1024;
    private volatile int height = 600;
    private volatile int captureHeight = 600; // phone's shape at headunit width
    private volatile int pictureHeight = 600; // captureHeight after stretch, centered in height
    private volatile MediaProjection projection;
    private volatile boolean selfTest;
    private volatile long selfTestAt;
    private boolean threadsStarted;
    private volatile boolean stopped;
    private Notification notification;
    private ImageReader reader;
    private VirtualDisplay display;
    private PowerManager.WakeLock wakeLock;
    private View orientationLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("mirror", "Mirroring", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, MirrorService.class).setAction(ACTION_STOP), 0);
        notification = new Notification.Builder(this, "mirror")
                .setContentTitle("PhoneLink is ready to mirror your screen")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        startForeground(NOTIFICATION_ID, notification);
        try {
            logFile = new FileWriter(new File(getExternalFilesDir(null), "plink.log"));
        } catch (IOException | NullPointerException e) {
            Log.e(TAG, "no log file", e);
        }
        log("service created, sdk=" + android.os.Build.VERSION.SDK_INT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification); // also covers a start that lands during shutdown
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            log("stopped from notification");
            if (connected) {
                // A blocked USB read can't be interrupted, so the link stays up until unplug and capture ends now.
                MediaProjection p = projection;
                if (p != null) p.stop();
            } else {
                shutdown();
            }
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_DATA) && projection == null) {
            selfTest = intent.getBooleanExtra(MainActivity.EXTRA_SELF_TEST, false);
            startProjection(intent.getIntExtra(EXTRA_RESULT, 0), intent.getParcelableExtra(EXTRA_DATA));
        }
        if (!threadsStarted) {
            threadsStarted = true;
            new Thread(this::usbLoop, "pl-usb").start();
            new Thread(this::frameLoop, "pl-frames").start();
        }
        return START_NOT_STICKY;
    }

    /**
     * Ends capture and this service but keeps the process, which also hosts TouchService. If the process
     * dies, Android marks that service as crashed, and Xiaomi then refuses to restart it, so touch stays
     * dead while Settings shows it enabled. Real case: Xiaomi 14T, 2026-11-09. Never call System.exit here.
     */
    private void shutdown() {
        stopped = true;
        TouchService.setEnabled(this, false);
        main.post(() -> {
            MediaProjection p = projection;
            projection = null;
            capturing = false;
            if (p != null) p.stop();
            applyActive();
            stopForeground(true);
            stopSelf();
        });
    }

    private void startProjection(int result, Intent data) {
        MediaProjection p = getSystemService(MediaProjectionManager.class).getMediaProjection(result, data);
        if (p == null) {
            log("no projection");
            return;
        }
        p.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                log("capture stopped by the system or user");
                capturing = false;
                projection = null;
                wholeScreen = true;
                applyActive();
            }
        }, main);
        projection = p;
        capturing = true;
        wholeScreen = true;
        if (selfTest) {
            displayKnown = true;
            sending = true;
            TouchService.setEnabled(this, true);
            new Thread(this::selfTestDrag, "pl-drag").start();
        }
        applyActive();
        log("capture approved");
    }

    /** Screen stays on and landscape only while actually mirroring. Runs on the main thread. */
    private void applyActive() {
        boolean active = projection != null && (connected || selfTest);
        if (wakeLock == null) {
            // Capture goes black if the screen sleeps.
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "phonelink:mirror");
        }
        if (active && !wakeLock.isHeld()) wakeLock.acquire();
        if (!active && wakeLock.isHeld()) wakeLock.release();
        WindowManager wm = getSystemService(WindowManager.class);
        if (active && orientationLock == null && Settings.canDrawOverlays(this)) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; // works with auto-rotate off
            orientationLock = new View(this);
            wm.addView(orientationLock, lp);
            log("landscape lock on");
        } else if (!active && orientationLock != null) {
            wm.removeView(orientationLock);
            orientationLock = null;
            log("landscape lock off");
        }
    }

    /**
     * Serves one headunit connection after another. Exits soon after unplug, which ends capture, so
     * Android's screen-share protection doesn't keep hiding notifications.
     */
    private void usbLoop() {
        UsbManager usb = getSystemService(UsbManager.class);
        long exitAt = System.currentTimeMillis() + FIRST_CONNECT_MS;
        while (true) {
            ParcelFileDescriptor pfd = null;
            while (pfd == null) {
                UsbAccessory[] list = usb.getAccessoryList();
                if (list != null && list.length > 0 && usb.hasPermission(list[0])) {
                    pfd = usb.openAccessory(list[0]);
                    log("accessory " + list[0] + " open=" + (pfd != null));
                }
                if (pfd == null) {
                    if (list == null && System.currentTimeMillis() > exitAt && !selfTest) {
                        log("nothing connected, stopping");
                        shutdown();
                        return;
                    }
                    if (stopped || !sleep(300)) return;
                }
            }
            serve(pfd);
            waitForAccessoryGone(usb);
            exitAt = System.currentTimeMillis() + UNPLUG_GRACE_MS;
        }
    }

    /**
     * Android still lists the accessory for about a second after USB drops. Opening it then succeeds but
     * never reads, and holds /dev/usb_accessory exclusively, so the next plug-in can't enter accessory
     * mode and the headunit reports USB debugging as off. Real case: car test 2026-11-09.
     */
    private void waitForAccessoryGone(UsbManager usb) {
        long deadline = System.currentTimeMillis() + ACCESSORY_GONE_MS;
        while (usb.getAccessoryList() != null && System.currentTimeMillis() < deadline) {
            if (!sleep(100)) return;
        }
        log("accessory gone=" + (usb.getAccessoryList() == null));
    }

    private void serve(ParcelFileDescriptor pfd) {
        out = new FileOutputStream(pfd.getFileDescriptor());
        connected = true;
        log("touch switched on=" + TouchService.setEnabled(this, true));
        main.post(this::applyActive);
        Protocol.Parser parser = new Protocol.Parser(this::onMessage);
        byte[] buffer = new byte[16384];
        try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                parser.feed(buffer, n);
            }
            log("read ended");
        } catch (IOException e) {
            log("read failed: " + e);
        }
        sending = false;
        connected = false;
        log("touch switched off=" + TouchService.setEnabled(this, false));
        wholeScreen = true;
        synchronized (this) {
            out = null;
        }
        try {
            pfd.close();
        } catch (IOException ignored) {
            // already gone with the USB connection
        }
        main.post(this::applyActive);
    }

    private void onMessage(int cmd, byte[] p) {
        if (cmd != Protocol.CMD_TOUCH) log("<- cmd " + cmd + " len " + p.length + ": " + Protocol.hex(p, 48));
        switch (cmd) {
            case Protocol.CMD_VERSION:
                write(Protocol.versionReply(p.length > 0 && p[0] != 0));
                break;
            case Protocol.CMD_SOFT_INFO:
                write(Protocol.softInfoReply());
                break;
            case Protocol.CMD_DISP_INFO:
                if (p.length >= 8) onDisplayInfo(p);
                break;
            case Protocol.CMD_CONTROL:
                if (p.length > 0 && p[0] == Protocol.CONTROL_SCREEN_START) {
                    wholeScreen = true;
                    sending = true;
                }
                break;
            case Protocol.CMD_TOUCH:
                if (p.length >= 5) onTouch(Protocol.le16(p, 0), Protocol.le16(p, 2), p[4]);
                break;
            case Protocol.CMD_KEY:
                if (p.length >= 3) onKey(Protocol.le16(p, 0), p[2]);
                break;
            default:
                break;
        }
    }

    private void onDisplayInfo(byte[] p) {
        width = Protocol.le16(p, 0);
        height = Protocol.le16(p, 2);
        int vw = Protocol.le16(p, 4);
        int vh = Protocol.le16(p, 6);
        Point real = realSize();
        write(Protocol.dispReply(width, height,
                Protocol.portraitWidth(vw, vh, Math.min(real.x, real.y), Math.max(real.x, real.y)), vh));
        displayKnown = true;
    }

    private void onTouch(int x, int y, int type) {
        Point real = realSize();
        int top = (height - pictureHeight) / 2;
        int[] phone = Protocol.toPhone(x, (y - top) * captureHeight / pictureHeight, width, captureHeight, real.x, real.y);
        boolean pressed = (type & 1) != 0; // same bit the old agent checks
        TouchService t = TouchService.instance;
        if (t != null) t.touch(pressed, phone[0], phone[1]);
    }

    /** Key ids from the old app's table: 0 = Home, 1 = Back. State 0 or 2 means released. */
    private void onKey(int id, int state) {
        TouchService t = TouchService.instance;
        if (t != null && state != 1) t.key(id);
    }

    private Point realSize() {
        Point p = new Point();
        getSystemService(DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(p);
        return p;
    }

    private void frameLoop() {
        long lastWrite = 0;
        long lastPlaceholder = 0;
        int[] raw = null;
        int[] cur = null;
        int[] prev = null;
        int count = 0;
        int lastStride = 0;
        while (!stopped && sleep(POLL_MS)) {
            if (projection == null && reader != null) {
                display.release();
                reader.close();
                display = null;
                reader = null;
                log("capture released");
            }
            if (!sending) {
                if (reader != null) {
                    Image old = reader.acquireLatestImage(); // don't hold the display back while unplugged
                    if (old != null) old.close();
                }
                continue;
            }
            long now = System.currentTimeMillis();
            if (projection == null) {
                if (now - lastPlaceholder >= 1000) {
                    lastPlaceholder = now;
                    write(placeholder());
                    lastWrite = now;
                } else if (now - lastWrite >= IDLE_FRAME_MS) {
                    write(Protocol.emptyFrame());
                    lastWrite = now;
                }
                continue;
            }
            if (reader == null && displayKnown) startCapture();
            if (selfTest && selfTestAt > 0 && now >= selfTestAt && lastStride > 0) {
                saveSelfTest(prev, lastStride, width, height);
                shutdown();
                break;
            }
            Image image = reader == null ? null : reader.acquireLatestImage();
            if (image == null) {
                if (now - lastWrite >= IDLE_FRAME_MS) {
                    write(Protocol.emptyFrame());
                    lastWrite = now;
                }
                continue;
            }
            Image.Plane plane = image.getPlanes()[0];
            int stride = plane.getRowStride() / 4;
            int w = width;
            int h = height;
            int rawH = captureHeight;
            if (cur == null || cur.length != stride * h || raw.length != stride * rawH) {
                raw = new int[stride * rawH];
                cur = new int[stride * h];
                prev = new int[stride * h];
                wholeScreen = true;
            }
            ByteBuffer buffer = plane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
            buffer.asIntBuffer().get(raw, 0, Math.min(raw.length, buffer.remaining() / 4));
            image.close();
            int pictureH = pictureHeight;
            Protocol.stretchRows(raw, stride, rawH, cur, (h - pictureH) / 2, pictureH);
            List<int[]> rects = wholeScreen
                    ? Collections.singletonList(new int[] {0, 0, w, h})
                    : Protocol.dirtyRects(cur, prev, stride, w, h);
            wholeScreen = false;
            if (rects.isEmpty()) {
                if (now - lastWrite >= IDLE_FRAME_MS) {
                    write(Protocol.emptyFrame());
                    lastWrite = now;
                }
                continue;
            }
            for (int[] r : rects) {
                write(Protocol.frame(r[0], r[1], r[2], r[3], Protocol.toRgb565(cur, stride, r[0], r[1], r[2], r[3])));
            }
            lastWrite = System.currentTimeMillis();
            if (++count % 100 == 1) {
                log("frame #" + count + " boxes=" + rects.size() + " first " + rects.get(0)[2] + "x" + rects.get(0)[3]
                        + " total ms=" + (lastWrite - now));
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
            lastStride = stride;
        }
        if (reader != null) {
            display.release();
            reader.close();
            display = null;
            reader = null;
        }
    }

    private void startCapture() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        Point real = realSize();
        captureHeight = Protocol.captureHeight(width, height, real.x, real.y);
        pictureHeight = Protocol.pictureHeight(captureHeight, height, STRETCH_PERCENT);
        reader =ImageReader.newInstance(width, captureHeight, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("phonelink", width, captureHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
        selfTestAt = System.currentTimeMillis() + 3000; // long enough for the landscape lock to settle
        log("capture started " + width + "x" + captureHeight + ", picture height " + pictureHeight + " of " + height);
    }

    /** Shown on the headunit until capture is approved on the phone. */
    private byte[] placeholder() {
        int w = width;
        int h = height;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(16, 32, 64));
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(h / 15f);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        c.drawText("Tap \"Start now\" on your phone", w / 2f, h / 2f, paint);
        ByteBuffer pixels = ByteBuffer.allocate(w * h * 2);
        b.copyPixelsToBuffer(pixels);
        return Protocol.frame(0, 0, w, h, pixels.array());
    }

    /** Runs a scripted vertical drag through the live touch path, to check it without the car. */
    private void selfTestDrag() {
        if (!sleep(1500)) return;
        TouchService t = TouchService.instance;
        if (t == null) {
            log("self test drag skipped: touch service off");
            return;
        }
        for (int y = 800; y >= 300; y -= 50) {
            t.touch(true, 1200, y);
            log("self test drag point y=" + y);
            if (!sleep(50)) return;
        }
        t.touch(false, 1200, 300);
        log("self test drag released");
    }

    /** Decodes the exact wire pixels back into a PNG, to check capture without the car. */
    private void saveSelfTest(int[] px, int stride, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        b.copyPixelsFromBuffer(ByteBuffer.wrap(Protocol.toRgb565(px, stride, 0, 0, w, h)));
        try (FileOutputStream f = new FileOutputStream(new File(getExternalFilesDir(null), "selftest.png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
            Point real = realSize();
            log("self test image saved, stride=" + stride + " screen=" + real.x + "x" + real.y);
        } catch (IOException e) {
            log("self test failed: " + e);
        }
    }

    private void write(byte[] data) {
        synchronized (this) {
            FileOutputStream o = out;
            if (o == null) return; // not connected, or self test
            try {
                if (data.length < 64 && data[1] != (byte) 0x83) log("-> " + Protocol.hex(data, 64));
                o.write(data);
            } catch (IOException e) {
                log("write failed: " + e);
            }
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void log(String text) {
        Log.i(TAG, text);
        synchronized (TAG) {
            if (logFile != null) {
                try {
                    logFile.write(System.currentTimeMillis() + " " + text + "\n");
                    logFile.flush();
                } catch (IOException ignored) {
                    // logcat still has it
                }
            }
        }
    }
}
