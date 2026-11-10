package com.phonelink.phonelinkserver;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Html;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One-step-at-a-time setup screen: allow landscape, turn on touch, start mirroring. Also asks for screen
 * capture approval. The headunit starts this with `am start`.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1;
    private static final String ACTION_PERMISSION = "com.phonelink.phonelinkserver.action.USB_PERMISSION";
    static final String EXTRA_SELF_TEST = "selftest";

    private static final int STEP_LANDSCAPE = 0;
    private static final int STEP_TOUCH = 1;
    private static final int STEP_MIRROR = 2;
    private static final int STEP_COUNT = 3;
    private static final long REFRESH_MS = 1000; // Android sends no callback when USB is unplugged

    private static final int MUTED = 0xFF4A5D6C;
    private static final int BODY = 0xFF34485A;
    private static final int PICTURE_GROUND = 0xFFE2ECF2;
    private static final int LOCKED_GROUND = 0xFFD7E0E6;
    private static final int WATERMARK = 0xFF566A7A;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private TextView stepLabel;
    private final View[] bars = new View[STEP_COUNT];
    private Illustration picture;
    private TextView title;
    private TextView body;
    private Button primary;
    private Button secondary;

    private int step;
    private String notice;
    private boolean captureAsked;
    private boolean permissionAsked;
    private boolean selfTest;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Illustration.GROUND);
        getWindow().setNavigationBarColor(Illustration.GROUND);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildScreen());
        handle(getIntent());
    }

    private View buildScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(24), dp(24), dp(24), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(18, Illustration.INK, true);
        name.setText("PhoneLink");
        header.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        stepLabel = text(13, MUTED, true);
        header.addView(stepLabel);
        screen.addView(header);

        LinearLayout progress = new LinearLayout(this);
        for (int i = 0; i < STEP_COUNT; i++) {
            bars[i] = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(6), 1);
            if (i > 0) lp.leftMargin = dp(6);
            progress.addView(bars[i], lp);
        }
        screen.addView(progress, below(14));

        picture = new Illustration(this);
        picture.setBackground(rounded(PICTURE_GROUND, 24));
        picture.setPadding(dp(16), dp(16), dp(16), dp(16));
        picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams pictureLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
        pictureLp.topMargin = dp(24);
        screen.addView(picture, pictureLp);

        title = text(32, Illustration.INK, true);
        screen.addView(title, below(24));
        body = text(16, BODY, false);
        body.setLineSpacing(dp(4), 1);
        screen.addView(body, below(10));

        View spacer = new View(this);
        spacer.setMinimumHeight(dp(24));
        screen.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        primary = button(54);
        primary.setOnClickListener(v -> onPrimary());
        screen.addView(primary, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        secondary = button(48);
        secondary.setBackground(null);
        secondary.setTextColor(BODY);
        secondary.setOnClickListener(v -> {
            step = Math.max(STEP_LANDSCAPE, step - 1);
            render();
        });
        LinearLayout.LayoutParams secondaryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        secondaryLp.topMargin = dp(8);
        screen.addView(secondary, secondaryLp);

        TextView watermark = text(12, WATERMARK, false);
        watermark.setText("arydev.id");
        watermark.setGravity(Gravity.CENTER);
        watermark.setLetterSpacing(0.03f);
        screen.addView(watermark, below(12));

        // Scrolls on small screens and while the phone is held in landscape for the car.
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Illustration.GROUND);
        scroll.setFillViewport(true);
        scroll.addView(screen);
        return scroll;
    }

    private LinearLayout.LayoutParams below(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(int heightDp) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(heightDp));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    /** With the permission granted, touch switches itself on with USB, so there's nothing to ask for. */
    private boolean touchReady() {
        return TouchService.instance != null || TouchService.canSwitch(this);
    }

    private boolean usbAttached() {
        return ((UsbManager) getSystemService(USB_SERVICE)).getAccessoryList() != null;
    }

    private int firstOpenStep() {
        if (!Settings.canDrawOverlays(this)) return STEP_LANDSCAPE;
        return touchReady() ? STEP_MIRROR : STEP_TOUCH;
    }

    private void render() {
        stepLabel.setText("Step " + (step + 1) + " of " + STEP_COUNT);
        for (int i = 0; i < STEP_COUNT; i++) {
            bars[i].setBackground(rounded(i <= step ? Illustration.ACCENT : Illustration.TRACK, 3));
        }
        picture.setStep(step);
        secondary.setVisibility(step == STEP_LANDSCAPE ? View.INVISIBLE : View.VISIBLE);
        if (step == STEP_LANDSCAPE) {
            renderLandscape();
        } else if (step == STEP_TOUCH) {
            renderTouch();
        } else {
            renderMirror();
        }
    }

    private void renderLandscape() {
        title.setText("Allow landscape");
        if (Settings.canDrawOverlays(this)) {
            body.setText("Allowed. Your phone turns sideways to fit the car screen.");
            setPrimary("Next: turn on touch", true);
        } else {
            body.setText("Your phone turns sideways to fit the car screen. On the next page, switch on PhoneLink.");
            setPrimary("Allow landscape", true);
        }
    }

    private void renderTouch() {
        title.setText("Turn on touch");
        secondary.setText("Back to landscape");
        if (TouchService.canSwitch(this)) {
            body.setText("Automatic on this phone. Touch turns on when you plug in and off when you unplug.");
            setPrimary("Next: start mirroring", true);
        } else if (TouchService.instance != null) {
            body.setText("On. Tap the car screen and your phone follows.");
            setPrimary("Next: start mirroring", true);
        } else {
            body.setText(Html.fromHtml("Tap the car screen and your phone follows. In Accessibility, find "
                    + "<b>PhoneLink touch</b> and switch it on.<br><br>Switch greyed out? Open PhoneLink's App info, "
                    + "open its menu, then choose Allow restricted settings.", 0));
            setPrimary("Open Accessibility settings", true);
        }
    }

    private void renderMirror() {
        secondary.setText("Back to touch");
        if (MirrorService.capturing && MirrorService.connected) {
            title.setText("Mirroring");
            body.setText("Your screen is showing on the car. Unplug any time to finish.");
            setPrimary("Stop mirroring", true);
        } else if (MirrorService.capturing) {
            title.setText("Waiting for your car");
            body.setText("Plug back in now to keep going without being asked again.");
            setPrimary("Stop mirroring", true);
        } else if (usbAttached()) {
            title.setText("Start mirroring");
            body.setText(notice != null ? notice
                    : "Connected to your car. Tap below, choose Entire screen if asked, then tap Start now.");
            setPrimary("Start mirroring", true);
        } else {
            title.setText("Start mirroring");
            body.setText("Plug into your car and PhoneLink opens by itself. Choose Entire screen if asked, "
                    + "then tap Start now.");
            setPrimary("Plug into your car first", false);
        }
    }

    private void setPrimary(String label, boolean enabled) {
        primary.setText(label);
        primary.setEnabled(enabled);
        primary.setTextColor(enabled ? 0xFFFFFFFF : MUTED);
        primary.setBackground(enabled
                ? new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(Illustration.ACCENT, 14), null)
                : rounded(LOCKED_GROUND, 14));
    }

    private void onPrimary() {
        if (step == STEP_LANDSCAPE) {
            if (Settings.canDrawOverlays(this)) {
                step = STEP_TOUCH;
                render();
            } else {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        } else if (step == STEP_TOUCH) {
            if (touchReady()) {
                step = STEP_MIRROR;
                render();
            } else {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        } else if (MirrorService.capturing) {
            startService(new Intent(this, MirrorService.class).setAction(MirrorService.ACTION_STOP));
        } else {
            startForegroundService(new Intent(this, MirrorService.class));
            askCapture();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        step = firstOpenStep();
        main.post(refresh);
        askUsbPermission();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(refresh);
    }

    /**
     * Asks for capture only once USB is attached. The headunit runs `am start` before switching USB to
     * accessory mode, and the attach intent that follows clears this singleTask stack, which would
     * dismiss a prompt already showing.
     */
    private void handle(Intent intent) {
        selfTest = intent.getBooleanExtra(EXTRA_SELF_TEST, false);
        boolean fromLauncher = intent.hasCategory(Intent.CATEGORY_LAUNCHER);
        boolean attached = usbAttached();
        if (fromLauncher && !attached) return; // just the setup screen
        startForegroundService(new Intent(this, MirrorService.class));
        if (MirrorService.capturing) {
            if (!fromLauncher) moveTaskToBack(true); // capture kept from a short unplug, so don't cover the phone
        } else if (selfTest || attached) {
            askCapture();
        }
    }

    private void askCapture() {
        if (captureAsked || MirrorService.capturing) return; // a second approval would just be thrown away
        captureAsked = true;
        notice = null;
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private void askUsbPermission() {
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        UsbAccessory[] list = usb.getAccessoryList();
        if (list != null && list.length > 0 && !usb.hasPermission(list[0]) && !permissionAsked) {
            permissionAsked = true;
            usb.requestPermission(list[0], PendingIntent.getBroadcast(this, 0, new Intent(ACTION_PERMISSION), 0));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_CAPTURE) return;
        captureAsked = false;
        if (result != RESULT_OK || data == null) {
            notice = "Screen capture was declined. Tap Start mirroring to try again.";
            return;
        }
        Intent service = new Intent(this, MirrorService.class);
        service.putExtra(MirrorService.EXTRA_RESULT, result);
        service.putExtra(MirrorService.EXTRA_DATA, data);
        service.putExtra(EXTRA_SELF_TEST, selfTest);
        startForegroundService(service);
        moveTaskToBack(true);
    }
}
