package com.krushock.itvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes this launcher the home screen on Google TV without switching Google TV's own home off. Google TV's home
 * always wins the Home button while it's enabled, but switching it off breaks voice search (the Assistant asks it
 * which mode the TV is in) and the Google Home panel. So it stays on, and this service:
 * <ul>
 *   <li>catches the Home button and opens this launcher instead (holding Home opens Google TV's panel, with the
 *       Google Home controls);</li>
 *   <li>switches to this launcher whenever Google TV's home comes up anyway: after a restart, or backing out of an
 *       app.</li>
 * </ul>
 * It sees only the Home key and window changes of Google TV's home and setup apps (see res/xml/home_key_service.xml);
 * it reads no screen content. Switched on over adb at install time, or in Settings → Accessibility.
 */
public class HomeKeyService extends AccessibilityService {

    static final String GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx";
    static final String SETUP_APP = "com.google.android.tungsten.setupwraith";

    private static HomeKeyService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean held;
    private final Runnable hold = () -> {
        held = true;
        openGoogleTvPanel();
    };

    /** Whether the service is on, i.e. this launcher has the Home button. */
    static boolean isRunning() {
        return running != null;
    }

    /** Gives Home back to Google TV: switches the service off (Settings → Accessibility switches it on again). */
    static void handBack() {
        if (running != null) running.disableSelf();
    }

    @Override
    protected void onServiceConnected() {
        running = this;
    }

    @Override
    public void onDestroy() {
        running = null;
        handler.removeCallbacks(hold);
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                held = false;
                handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout() + 300);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(hold);
            if (!held) openHome();
        }
        return true;   // Google TV never sees the press
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null) return;
        String c = cls.toString();
        // Google TV's home (normal or basic mode) or the setup app's stand-in home: come back here. Its panel and
        // profile picker are left alone.
        boolean googleHome = GOOGLE_TV_HOME.contentEquals(pkg) && c.endsWith("HomeActivity");
        boolean setupHome = SETUP_APP.contentEquals(pkg) && c.endsWith("RecoveryActivity");
        if (googleHome || setupHome) openHome();
    }

    @Override
    public void onInterrupt() {
    }

    private void openHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            // No transition: when Google TV's home flashes up, ours replaces it as quickly as possible.
            startActivity(home, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        } catch (RuntimeException ignored) {
            // launcher being updated: the next Home press tries again
        }
    }

    /** Google TV's side panel: profile, settings shortcuts and the Google Home controls. */
    private void openGoogleTvPanel() {
        try {
            startActivity(new Intent("android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            openHome();   // no panel on this TV: a long press is just Home
        }
    }
}
