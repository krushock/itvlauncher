package com.krushock.itvlauncher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;

/**
 * The glass popups' panel (layout popup_inputs). Up with nothing above it in the popup, on the top row of a menu or a
 * folder, closes the popup, so you can leave it the way you came in instead of only with Back. Esc counts as Back.
 */
public class GlassPanel extends LinearLayout {

    private Runnable onUpAtTop;

    public GlassPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setOnUpAtTop(Runnable r) {
        onUpAtTop = r;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            // A keyboard's Esc (the emulator's, a USB or Bluetooth one) does what the remote's Back does. The popup
            // window only knows Back, so hand it a Back press instead.
            KeyEvent back = new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                    KeyEvent.KEYCODE_BACK, event.getRepeatCount(), event.getMetaState());
            return getRootView().dispatchKeyEvent(back);
        }
        if (onUpAtTop != null && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            View focused = findFocus();
            // The popup is its own window, so a search that finds nothing above means the top row.
            View above = focused != null ? focused.focusSearch(View.FOCUS_UP) : null;
            if (focused != null && (above == null || !isAncestorOf(above))) {
                onUpAtTop.run();
                return true;
            }
        }
        // A view in the popup that moves focus itself (the app switcher's scrolling row) leaves Android's
        // navigation click out; play it, as for the launcher's own moves (MainActivity.click).
        View before = event.getAction() == KeyEvent.ACTION_DOWN ? findFocus() : null;
        boolean handled = super.dispatchKeyEvent(event);
        if (handled && before != null) {
            View now = findFocus();
            if (now != null && now != before) MainActivity.click(now, event.getKeyCode());
        }
        return handled;
    }

    private boolean isAncestorOf(View v) {
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == this) return true;
        }
        return false;
    }
}
