package com.phonelink.phonelinkserver;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Performs headunit touches and Home/Back on the phone. Reads nothing from other apps.
 * A touch is streamed as short continued strokes, so drags follow the finger live. Android runs one
 * stroke at a time, so points that arrive during a stroke wait and go out together in the next one.
 */
public class TouchService extends AccessibilityService {
    static volatile TouchService instance;
    private static volatile boolean wanted;
    private static final String TAG = "PLINK";
    private static final long SEGMENT_MS = 30; // headunit sends a point about every 50 ms

    private final List<int[]> pending = new ArrayList<>();
    private GestureDescription.StrokeDescription stroke;
    private boolean down;
    private boolean released;
    private boolean inFlight;
    private int lastX, lastY;

    private final GestureResultCallback callback = new GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription gesture) {
            synchronized (TouchService.this) {
                inFlight = false;
                if (stroke != null && !stroke.willContinue()) {
                    reset();
                } else {
                    pump();
                }
            }
        }

        @Override
        public void onCancelled(GestureDescription gesture) {
            // Happens if someone touches the phone itself. The next point starts a fresh touch.
            Log.i(TAG, "gesture cancelled");
            synchronized (TouchService.this) {
                inFlight = false;
                reset();
            }
        }
    };

    static boolean canSwitch(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Turns this service on or off in system settings, so banking apps don't see it while unplugged.
     * Needs the one-time adb grant. Without it, touch stays the user's manual switch.
     */
    static synchronized boolean setEnabled(Context c, boolean on) {
        wanted = on;
        if (!canSwitch(c)) return false;
        ComponentName me = new ComponentName(c, TouchService.class);
        ContentResolver r = c.getContentResolver();
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String list = Protocol.withService(Settings.Secure.getString(r, key), me.flattenToString(), me.flattenToShortString(), on);
        try {
            Settings.Secure.putString(r, key, list);
            Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, list.isEmpty() ? 0 : 1);
            return true;
        } catch (SecurityException e) {
            Log.i(TAG, "touch switch refused: " + e);
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.i(TAG, "touch service connected");
        if (!wanted && canSwitch(this)) setEnabled(this, false); // left on by a crash or reboot
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    synchronized void touch(boolean pressed, int x, int y) {
        if (!down) {
            if (!pressed) return;
            down = true;
            released = false;
            stroke = null;
            pending.clear();
            pending.add(new int[] {x, y});
        } else if (x != lastX || y != lastY) {
            pending.add(new int[] {x, y});
        }
        lastX = x;
        lastY = y;
        if (!pressed) released = true;
        pump();
    }

    private void pump() {
        if (inFlight || !down || (pending.isEmpty() && !released)) return;
        Path path = new Path();
        int from = 0;
        if (stroke == null) {
            path.moveTo(pending.get(0)[0], pending.get(0)[1]);
            from = 1;
        } else {
            path.moveTo(endX, endY);
        }
        for (int i = from; i < pending.size(); i++) {
            path.lineTo(pending.get(i)[0], pending.get(i)[1]);
        }
        if (!pending.isEmpty()) {
            int[] end = pending.get(pending.size() - 1);
            endX = end[0];
            endY = end[1];
        }
        pending.clear();
        boolean more = !released;
        stroke = stroke == null
                ? new GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, more)
                : stroke.continueStroke(path, 0, SEGMENT_MS, more);
        inFlight = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), callback, null);
        if (!inFlight) {
            Log.i(TAG, "gesture refused");
            reset();
        }
    }

    private int endX, endY;

    private void reset() {
        down = false;
        released = false;
        stroke = null;
        pending.clear();
    }

    void key(int id) {
        if (id == 0) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        } else if (id == 1) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }
}
