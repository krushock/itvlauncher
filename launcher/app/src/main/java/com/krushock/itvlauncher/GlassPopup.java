package com.krushock.itvlauncher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * The glass dropdown (layout popup_inputs) behind every menu: Inputs, the tile menu, folders, hidden apps,
 * the quick panel and profiles. Rows use layout item_input.
 */
final class GlassPopup {

    final PopupWindow window;
    /** Every popup on screen, so Home can close them all. */
    private static final java.util.List<java.lang.ref.WeakReference<PopupWindow>> open = new java.util.ArrayList<>();

    /** Closes every glass popup that's showing (Home pressed, or the launcher left the screen). */
    static void dismissAll() {
        for (java.lang.ref.WeakReference<PopupWindow> ref : new java.util.ArrayList<>(open)) {
            PopupWindow w = ref.get();
            if (w != null && w.isShowing()) w.dismiss();
        }
        open.clear();
    }
    final LinearLayout rows;
    private final View panel;
    private final Activity activity;
    private final ViewGroup root;
    private final float density;
    private final GlassMaterial material;

    GlassPopup(Activity activity, ViewGroup root) {
        this.activity = activity;
        this.root = root;
        density = activity.getResources().getDisplayMetrics().density;
        panel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        rows = panel.findViewById(R.id.rows);
        material = new GlassMaterial(density, Look.light(activity));
        panel.setBackground(material);
        window = new PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        window.setOutsideTouchable(true);
        window.setAnimationStyle(0);
        window.setElevation(0f);
        // Up from the top row closes it, like Back.
        ((GlassPanel) panel).setOnUpAtTop(window::dismiss);
    }

    /**
     * A sub-menu: Up from its top row goes back to the menu it was opened from ({@code reopenParent} shows that menu
     * again, on the row that opened this one) instead of closing everything.
     */
    void upTo(Runnable reopenParent) {
        ((GlassPanel) panel).setOnUpAtTop(() -> {
            window.dismiss();
            // Not panel.post(): the dismissed panel is detached, and a detached view holds posts until it's attached.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(reopenParent);
        });
    }

    private CharSequence focusTitle;

    /** When shown, start on the row with this title instead of the first one (null: the first). */
    void focusRow(CharSequence title) {
        focusTitle = title;
    }

    /** For the text prompt: Up there must not throw away what was typed. */
    void keepOpenOnUp() {
        ((GlassPanel) panel).setOnUpAtTop(null);
    }

    boolean isEmpty() {
        return rows.getChildCount() == 0;
    }

    View addRow(int iconRes, CharSequence title, Runnable action) {
        return addRow(activity.getDrawable(iconRes), true, title, null, false, true, action);
    }

    /**
     * One row. App icons keep their colours ({@code tint} false). {@code status} shows small text on the right,
     * {@code dot} a green dot before it. Rows that toggle something keep the popup open ({@code dismiss} false).
     */
    View addRow(Drawable icon, boolean tint, CharSequence title, CharSequence status, boolean dot, boolean dismiss,
                Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ImageView iconView = row.findViewById(R.id.icon);
        if (!tint) iconView.setImageTintList(null);
        iconView.setImageDrawable(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        setStatus(row, status, dot);
        row.setOnClickListener(v -> {
            if (dismiss) window.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }

    static void setStatus(View row, CharSequence status, boolean dot) {
        TextView s = row.findViewById(R.id.status);
        s.setText(status);
        s.setVisibility(status != null ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.dot).setVisibility(dot ? View.VISIBLE : View.GONE);
    }

    void addSeparator() {
        View line = new View(activity);
        line.setBackgroundColor(activity.getColor(R.color.panel_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    /** A small dim heading, e.g. the folder name above its apps. */
    void addTitle(CharSequence text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(activity.getColor(R.color.text_tertiary));
        t.setTextSize(13f);
        t.setAllCaps(true);
        t.setLetterSpacing(0.14f);
        t.setTypeface(activity.getResources().getFont(R.font.outfit));
        int p = (int) (12 * density);
        t.setPadding(p, (int) (6 * density), p, (int) (8 * density));
        rows.addView(t);
    }

    /** A short explanation under a title, wrapped to the popup's width. */
    void addNote(CharSequence text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(activity.getColor(R.color.text_secondary));
        t.setTextSize(15f);
        t.setLineSpacing(0f, 1.15f);
        t.setTypeface(activity.getResources().getFont(R.font.outfit));
        t.setMaxWidth((int) (420 * density));
        int p = (int) (12 * density);
        t.setPadding(p, 0, p, (int) (10 * density));
        rows.addView(t);
    }

    void addView(View v) {
        rows.addView(v);
    }

    /** Under the anchor, or above it when there's no room below; kept on screen at the right edge too. */
    void showAt(View anchor, int minWidth) {
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        int w = Math.max(minWidth, panel.getMeasuredWidth());
        window.setWidth(w);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int gap = (int) (10 * density);
        int h = capHeight(panel.getMeasuredHeight(), root.getHeight() - 2 * gap);
        int below = loc[1] + anchor.getHeight() + gap;
        boolean above = below + h > root.getHeight() && loc[1] - gap - h >= 0;
        // Fits neither way: stay below, pushed up just enough to keep the whole menu on screen.
        int y = above ? loc[1] - gap - h : Math.max(gap, Math.min(below, root.getHeight() - h - gap));
        int x = Math.max(gap, Math.min(loc[0], root.getWidth() - w - (int) (40 * density)));
        show(x, y, above ? h : 0f, above ? 8 : -8);
    }

    /** Centred near the top of the screen, above where the on-screen keyboard appears. */
    void showNearTop(int minWidth) {
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = Math.max(minWidth, panel.getMeasuredWidth());
        window.setWidth(w);
        int y = (int) (110 * density);
        capHeight(panel.getMeasuredHeight(), root.getHeight() - y - (int) (10 * density));
        show((root.getWidth() - w) / 2, y, 0f, -8);
    }

    /** In the middle of the screen, e.g. an open folder. */
    void showCentered() {
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        // No wider than the screen's margins; a long row (the app switcher) scrolls inside.
        int w = Math.min(panel.getMeasuredWidth(), root.getWidth() - (int) (112 * density));
        int h = capHeight(panel.getMeasuredHeight(), root.getHeight() - (int) (40 * density));
        window.setWidth(w);
        show((root.getWidth() - w) / 2, (root.getHeight() - h) / 2, h / 2f, 0);
    }

    /**
     * The frosted glass: an eighth-size copy of the screen under the panel, blurred, becomes its backdrop. The copy
     * comes from the launcher's own window (PixelCopy), so it's what's really on screen; if the TV can't copy it,
     * the panel keeps its solid tint.
     */
    private void captureBackdrop(int x, int y) {
        View decor = activity.getWindow().getDecorView();
        int w = window.getWidth() > 0 ? window.getWidth() : panel.getMeasuredWidth();
        int h = window.getHeight() > 0 ? window.getHeight() : panel.getMeasuredHeight();
        Rect area = new Rect(x, y, x + w, y + h);
        if (w <= 0 || h <= 0 || !area.intersect(0, 0, decor.getWidth(), decor.getHeight())) return;
        int scale = 8;
        Bitmap small = Bitmap.createBitmap(Math.max(1, area.width() / scale), Math.max(1, area.height() / scale),
                Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(activity.getWindow(), area, small, result -> {
                if (result != PixelCopy.SUCCESS) return;
                GlassMaterial.blur(small, 4);
                material.setBackdrop(small, (float) area.width() / small.getWidth());
            }, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException ignored) {
            // no surface to copy yet: the solid tint stays
        }
    }

    /** A popup taller than {@code max} gets that height and scrolls inside; returns the height it will have. */
    private int capHeight(int h, int max) {
        if (h <= max || max <= 0) return h;
        window.setHeight(max);
        return max;
    }

    private void show(int x, int y, float pivotY, int fromDp) {
        // Drop in: fade and unfold from the edge nearest the anchor.
        panel.setAlpha(0f);
        panel.setPivotY(pivotY);
        boolean still = Look.reduceMotion(activity);   // Reduce Motion: fade only
        panel.setScaleY(still ? 1f : 0.92f);
        panel.setTranslationY(still ? 0f : fromDp * density);
        captureBackdrop(x, y);
        window.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
        open.removeIf(r -> r.get() == null || !r.get().isShowing());
        open.add(new java.lang.ref.WeakReference<>(window));
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.requestFocus();   // the first focusable row (or tile, in a folder)
        if (focusTitle != null) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                View r = rows.getChildAt(i);
                TextView t = r.findViewById(R.id.title);
                if (t != null && focusTitle.toString().contentEquals(t.getText())) {
                    r.requestFocus();
                    break;
                }
            }
        }
    }

    /**
     * A one-line text box (with the on-screen keyboard) for naming a folder or a profile. It starts empty with
     * {@code initial} as the hint, and saving it empty keeps {@code initial}: TV keyboards don't reliably replace
     * selected text. Shown near the top of the screen, clear of the keyboard.
     */
    static void prompt(Activity activity, ViewGroup root, CharSequence title, String initial, Consumer<String> done) {
        GlassPopup p = new GlassPopup(activity, root);
        p.keepOpenOnUp();
        p.addTitle(title);
        float d = p.density;
        EditText edit = new EditText(activity);
        edit.setHint(initial);
        edit.setHintTextColor(activity.getColor(R.color.text_secondary));   // readable: it's the suggestion
        edit.setSingleLine(true);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setTextColor(activity.getColor(R.color.text_primary));
        edit.setTextSize(18f);
        edit.setTypeface(activity.getResources().getFont(R.font.outfit));
        edit.setBackgroundResource(R.drawable.input_row_bg_dim);
        int pad = (int) (12 * d);
        edit.setPadding(pad, pad, pad, pad);
        edit.setMinWidth((int) (300 * d));
        Runnable save = () -> {
            String text = edit.getText().toString().trim();
            p.window.dismiss();
            done.accept(text.isEmpty() ? initial : text);
        };
        edit.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                save.run();
                return true;
            }
            return false;
        });
        p.addView(edit);
        View saveRow = p.addRow(activity.getDrawable(R.drawable.ic_check), true, activity.getString(R.string.save),
                null, false, false, save);
        if (initial != null && !initial.isEmpty()) {
            // While the box is empty, Save says what it will keep: "Save as “Games”".
            TextView saveTitle = saveRow.findViewById(R.id.title);
            Runnable label = () -> saveTitle.setText(edit.getText().toString().trim().isEmpty()
                    ? activity.getString(R.string.save_as, initial) : activity.getString(R.string.save));
            label.run();
            edit.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence t, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence t, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable e) {
                    label.run();
                }
            });
        }
        p.window.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        p.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        p.showNearTop((int) (360 * d));
        edit.requestFocus();
        edit.postDelayed(() -> {
            InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }
}
