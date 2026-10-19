package com.krushock.itvlauncher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots: chosen in Controls, else config.xml (HomeLayout.columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private HomeLayout layout;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private ImageView btnWifi;
    private ImageView btnNotifications;
    private View btnControls;
    /** Now Playing pill in the top bar, and the session it's showing. */
    private View btnNowPlaying;
    private android.media.session.MediaController npController;
    private android.media.session.MediaController.Callback npCallback;
    private android.media.session.MediaSessionManager.OnActiveSessionsChangedListener npSessions;
    private final Runnable npRecheck = this::updateNowPlaying;
    /** When the pill may hide (uptime ms), or 0: it waits out the gaps an app leaves while pausing or skipping. */
    private long npHideAt;
    private LinearLayout topButtons;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    /** Key of the tile being moved, or null when not in move mode. */
    private String movingKey;
    /** Order before the move started, for Back to cancel it. */
    private List<String> moveSnapshot;
    private ObjectAnimator wiggle;
    /** The open tile menu, so a long press can't open a second one. */
    private GlassPopup tileMenu;
    /** The current OK press began in move mode (it placed the tile), so holding it must not open the menu. */
    private boolean pressBegunInMove;
    /** Direction of the last arrow press on a tile and when, for the focus tilt. */
    private int lastArrow;
    private long lastArrowAt;
    private boolean resumed;
    /** The TV woke while Home wasn't in front: ask for the profile once it is. */
    private boolean askProfileWhenResumed;
    private Intent searchIntent;
    private TopShelf shelf;
    /** The top shelf has rows for the focused dock app, so the title sits smaller above it. */
    private boolean shelfShown;
    private float heroDockTop;
    private Weather weather;
    private ImageView backdrop;
    private View backdropScrim;
    private boolean backdropShown;
    private View weatherBox;
    private ImageView weatherIcon;
    private TextView weatherText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            connectedAt = SystemClock.uptimeMillis();
            handler.post(() -> {
                updateWifiLabel();
                // Back online (or online for the first time after boot): retry what needed the network.
                weather.onOnline(MainActivity.this::updateWeather);
                shelf.onOnline();
            });
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            handler.post(MainActivity.this::updateWifiLabel);   // e.g. internet confirmed, or lost behind the router
        }
    };
    /** When the current network came up, so a moment without confirmed internet still counts as connecting. */
    private volatile long connectedAt;
    /** The Wi-Fi icon now, so frequent signal updates don't restart the connecting animation. */
    private int wifiIcon;
    /** Wi-Fi turning on, joining, getting an address, and signal strength changes. */
    private final BroadcastReceiver wifiState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateWifiLabel();
        }
    };
    /** "Choose profile on wake", Apple TV style. */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!layout.askOnWake() || !layout.hasSeveralProfiles()) return;
            if (resumed) grid.post(() -> showProfiles(btnControls, false));
            else askProfileWhenResumed = true;
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            updateWeather();   // hides a reading that got too old while offline
            weather.refreshIfStale(MainActivity.this::updateWeather);
            if (Look.light(MainActivity.this) != createdLight && movingKey == null) {
                recreate();   // Automatic appearance: morning or evening switch
                return;
            }
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    /** Whether this activity was created light (Automatic switches at 7:00 and 19:00). */
    private boolean createdLight;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Light or dark resources and the text size, from Controls → Display.
        applyOverrideConfiguration(Look.override(base));
        createdLight = Look.light(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = HomeLayout.columns(this);
        AppAdapter.computeSize(this, COLUMNS);
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        btnControls = findViewById(R.id.btnControls);
        topButtons = findViewById(R.id.topButtons);
        weatherBox = findViewById(R.id.weatherBox);
        weatherIcon = findViewById(R.id.weatherIcon);
        weatherText = findViewById(R.id.weatherText);
        weather = new Weather(this);
        shelf = new TopShelf(this, findViewById(R.id.shelf), new TopShelf.Host() {
            @Override
            public void onShelfExit() {
                focusTile(lastFocused);
            }

            @Override
            public void onShelfUp(View card) {
                focusTopBarAbove(card);
            }

            @Override
            public void onCardFocused(TopShelf.Item item, int index) {
                showBackdrop(item);
                scheduleTakeover(item, index);
            }
        });
        setUpFullShelf();
        backdrop = findViewById(R.id.backdrop);
        backdropScrim = findViewById(R.id.backdropScrim);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(this, COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        layout = adapter.layout();
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = AppAdapter.itemHeight(density) + (int) (18 * density);   // plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnSearch = findViewById(R.id.btnSearch);
        searchIntent = findSearch();
        btnSearch.setVisibility(searchIntent != null ? View.VISIBLE : View.GONE);
        btnSearch.setOnClickListener(v -> open(new Intent(searchIntent)));
        findViewById(R.id.btnRecent).setOnClickListener(v -> showRecents());
        btnNotifications = findViewById(R.id.btnNotifications);
        btnNotifications.setOnClickListener(this::showNotifications);
        btnNowPlaying = findViewById(R.id.btnNowPlaying);
        AppAdapter.roundCorners(findViewById(R.id.npPillArt), 16 * density);
        btnNowPlaying.setOnClickListener(this::showPlayer);
        btnNowPlaying.setOnLongClickListener(v -> {
            if (npController != null) NowPlaying.open(this, npController);
            return true;
        });
        MediaWatcher.setListener(() -> handler.post(this::updateBell));
        updateBell();
        findViewById(R.id.btnInputs).setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnControls.setOnClickListener(this::showQuickPanel);

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && adapter.getItemCount() > 0) {
                // The top shelf sits between the bar and the dock: land on the card you left it from. Without a
                // shelf, back to the tile you came up from, not the grid's first one.
                if (shelf.hasItems()) shelf.focusLast();
                else focusTile(lastFocused);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // Straight to the neighbouring button: Android's focus search can prefer a Top Shelf card or a
                // tile that happens to reach further sideways than the next button.
                View next = neighbourButton(v, keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (next != null) next.requestFocus();
                else nudge(v, keyCode);   // the ends of the bar hold focus
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                nudge(v, keyCode);
                return true;
            }
            return false;
        };
        for (int i = 0; i < topButtons.getChildCount(); i++) topButtons.getChildAt(i).setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card && newF.getParent() != null
                    && grid.findContainingItemView(newF) != null;
            if (!onTile) hideGlass();
            boolean onShelf = newF != null && newF.getId() == R.id.shelfCard;
            if (!onShelf) handler.removeCallbacks(takeover);   // only resting on a card takes over the screen
            if (!onShelf && !fullShown) {
                hideBackdrop();
                skipTakeoverFor = -1;
            }
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::reanchorGlass);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        registerSystemReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        IntentFilter wifiFilter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        wifiFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        wifiFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        wifiFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        registerSystemReceiver(wifiState, wifiFilter);
    }

    /** For the system's own broadcasts only: other apps can't send to it (Android 13 asks to say so). */
    private void registerSystemReceiver(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        adapter.reload();
        if (!Look.reduceMotion(this)) grid.scheduleLayoutAnimation();
        shelf.invalidate();   // the app we come back from may have updated its Continue watching
        updateGreeting();
        updateWifiLabel();
        updateBell();
        startNowPlaying();
        updateWeather();
        weather.refreshIfStale(this::updateWeather);
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            if (askProfileWhenResumed) {
                askProfileWhenResumed = false;
                showProfiles(btnControls, false);
            }
        });
    }

    /** Home pressed while Home is already on screen: back to a clean home screen, like Apple TV's TV button. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetToHome();
    }

    private void resetToHome() {
        GlassPopup.dismissAll();
        handler.removeCallbacks(takeover);
        if (fullShown) exitFullShelf(false);
        if (movingKey != null) endMove(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        GlassPopup.dismissAll();
        resumed = false;
        stopNowPlaying();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(takeover);
        if (fullShown) exitFullShelf(false);   // come back to the normal home screen
        if (movingKey != null) endMove(true);   // Home or an app on top while moving keeps the new spot
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        unregisterReceiver(screenOn);
        unregisterReceiver(wifiState);
        MediaWatcher.setListener(null);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        Rect r = tileRect(card);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown || Look.reduceMotion(this)) {
            // First appearance, or Reduce Motion: no spring, the highlight is simply there.
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (!glassShown) glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        if (movingKey == null && SystemClock.uptimeMillis() - lastArrowAt < 400) tilt(card, lastArrow);
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    /** Tile rect in root coordinates, using layout positions (ignores the scale animation). */
    private Rect tileRect(View card) {
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        return r;
    }

    /**
     * Keeps the glass on the focused tile after a layout pass. The scroll listener only follows scrolls,
     * but a layout can move the tile too: at boot the dock padding is applied after the first tile is focused.
     */
    private void reanchorGlass() {
        if (!glassShown) return;
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || glass.getWidth() == 0) return;
        if (grid.findContainingItemView(f) == null) return;
        if (movingKey != null) return;   // followMovedTile() places it
        Rect r = tileRect(f);
        float x = r.exactCenterX() - glass.getWidth() / 2f;
        float y = r.exactCenterY() - glass.getHeight() / 2f;
        if (Math.abs(x - glassTargetX) < 1f && Math.abs(y - glassTargetY) < 1f) return;
        glassTargetX = x;
        glassTargetY = y;
        if (springX.isRunning()) springX.animateToFinalPosition(x);
        else glass.setTranslationX(x);
        if (springY.isRunning()) springY.animateToFinalPosition(y);
        else glass.setTranslationY(y);
    }

    // ---- motion: Apple TV's parallax, D-pad style ----------------------------------------------

    /** The newly focused tile swings in from the direction you came, then settles flat. */
    private void tilt(View card, int arrow) {
        if (Look.reduceMotion(this)) return;
        float deg = 7f;
        card.animate().cancel();
        switch (arrow) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: card.setRotationY(-deg); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: card.setRotationY(deg); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: card.setRotationX(deg); break;
            case KeyEvent.KEYCODE_DPAD_UP: card.setRotationX(-deg); break;
            default: return;
        }
        float s = AppAdapter.FOCUS_SCALE;
        card.animate().rotationX(0f).rotationY(0f).scaleX(s).scaleY(s).setDuration(420)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
    }

    /** Pressing against an edge: the tile (and the glass) bump that way and spring back, so it feels solid. */
    private void nudge(View v, int arrow) {
        if (Look.reduceMotion(this)) return;
        float d = 9 * density;
        float dx = arrow == KeyEvent.KEYCODE_DPAD_LEFT ? -d : arrow == KeyEvent.KEYCODE_DPAD_RIGHT ? d : 0;
        float dy = arrow == KeyEvent.KEYCODE_DPAD_UP ? -d : arrow == KeyEvent.KEYCODE_DPAD_DOWN ? d : 0;
        v.animate().translationX(dx).translationY(dy).setDuration(70).withEndAction(() ->
                v.animate().translationX(0f).translationY(0f).setDuration(200)
                        .setInterpolator(new OvershootInterpolator(2.5f)).start()).start();
        if (glassShown && v.getId() == R.id.card) {
            springX.animateToFinalPosition(glassTargetX + dx);
            springY.animateToFinalPosition(glassTargetY + dy);
            handler.postDelayed(() -> {
                springX.animateToFinalPosition(glassTargetX);
                springY.animateToFinalPosition(glassTargetY);
            }, 70);
        }
    }

    /** Focuses the top bar button closest (left to right) to {@code from}, for leaving the top shelf upwards. */
    private void focusTopBarAbove(View from) {
        int[] at = new int[2];
        from.getLocationInWindow(at);
        float cx = at[0] + from.getWidth() / 2f;
        View best = null;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < topButtons.getChildCount(); i++) {
            View b = topButtons.getChildAt(i);
            if (b.getVisibility() != View.VISIBLE) continue;
            b.getLocationInWindow(at);
            float d = Math.abs(at[0] + b.getWidth() / 2f - cx);
            if (d < bestDist) {
                bestDist = d;
                best = b;
            }
        }
        if (best != null) best.requestFocus();
    }

    /** The next visible top bar button to the left (-1) or right (+1), or null at the end of the bar. */
    private View neighbourButton(View from, int step) {
        for (int i = topButtons.indexOfChild(from) + step; i >= 0 && i < topButtons.getChildCount(); i += step) {
            View b = topButtons.getChildAt(i);
            if (b.getVisibility() == View.VISIBLE) return b;
        }
        return null;
    }

    // ---- top shelf -------------------------------------------------------------------------------

    /**
     * Apple TV's full-screen Top Shelf, without the trailers: the focused card's artwork fills the screen behind
     * everything, dimmed by a gradient. Only for real artwork (Up Next and app rows), not Recent's app banners.
     */
    private void showBackdrop(TopShelf.Item item) {
        if (item.art == null || item.art.isEmpty()) {
            hideBackdrop();
            return;
        }
        if (item.art.equals(backdrop.getTag(R.id.banner)) && backdropShown) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        shelf.images().load(item.art, backdrop, dm.widthPixels / 2, dm.heightPixels / 2, ok -> {
            if (!ok) return;
            backdropShown = true;
            backdrop.setAlpha(0.4f);
            backdrop.animate().alpha(1f).setDuration(400).start();
            backdropScrim.animate().alpha(1f).setDuration(300).start();
        });
    }

    // ---- full-screen top shelf (the Apple TV app's) ----------------------------------------------

    private static final long TAKEOVER_MS = 3000L;
    private View fullShelf, content, shelfView;
    private TextView fsApp, fsTitle, fsSub, fsDesc, fsPlay, fsInfo;
    private LinearLayout fsDots;
    private List<TopShelf.Item> fsItems = new ArrayList<>();
    private int fsIndex, takeoverIndex, skipTakeoverFor = -1;
    private boolean fullShown;
    private final Runnable takeover = () -> {
        View f = getCurrentFocus();
        if (!resumed || movingKey != null || f == null || f.getId() != R.id.shelfCard) return;
        enterFullShelf(shelf.items(), takeoverIndex);
    };

    private void setUpFullShelf() {
        fullShelf = findViewById(R.id.fullShelf);
        content = findViewById(R.id.content);
        shelfView = findViewById(R.id.shelf);
        fsApp = findViewById(R.id.fsApp);
        fsTitle = findViewById(R.id.fsTitle);
        fsSub = findViewById(R.id.fsSub);
        fsDesc = findViewById(R.id.fsDesc);
        fsPlay = findViewById(R.id.fsPlay);
        fsInfo = findViewById(R.id.fsInfo);
        fsDots = findViewById(R.id.fsDots);
        fsPlay.setOnClickListener(v -> {
            TopShelf.Item it = fsItems.get(fsIndex);
            exitFullShelf(false);
            shelf.open(it);
        });
        // Android apps don't share a details page, so More Info opens the app itself.
        fsInfo.setOnClickListener(v -> {
            TopShelf.Item it = fsItems.get(fsIndex);
            exitFullShelf(false);
            Intent i = it.pkg != null ? getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg) : null;
            if (i != null) {
                Recents.record(this, it.pkg);
                open(i);
            }
        });
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (v == fsInfo) {
                        fsPlay.requestFocus();
                    } else if (fsIndex > 0) {
                        page(-1);
                        click(v, keyCode);
                    } else {
                        nudge(v, keyCode);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (v == fsPlay) {
                        fsInfo.requestFocus();
                    } else if (fsIndex < fsItems.size() - 1) {
                        page(1);
                        click(v, keyCode);
                    } else {
                        nudge(v, keyCode);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    exitFullShelf(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    exitFullShelf(true);   // and on up to the top bar, as Up from a shelf card does
                    View card = shelf.cardAt(fsIndex);
                    if (card != null) focusTopBarAbove(card);
                    return true;
                default:
                    return false;
            }
        };
        fsPlay.setOnKeyListener(keys);
        fsInfo.setOnKeyListener(keys);
    }

    /** Rested on a shelf card with artwork: take over the screen after a moment, as Apple TV does. */
    private void scheduleTakeover(TopShelf.Item item, int index) {
        handler.removeCallbacks(takeover);
        if (fullShown || item.art == null || item.art.isEmpty() || index == skipTakeoverFor) return;
        skipTakeoverFor = -1;
        takeoverIndex = index;
        handler.postDelayed(takeover, TAKEOVER_MS);
    }

    private void enterFullShelf(List<TopShelf.Item> items, int index) {
        if (items.isEmpty()) return;
        fsItems = items;
        fsIndex = Math.max(0, Math.min(index, items.size() - 1));
        fullShown = true;
        boolean still = Look.reduceMotion(this);
        hideGlass();
        // The home screen slides away; the artwork, title and buttons take its place. The shelf and its title are
        // placed with setY(), so they only fade.
        content.animate().alpha(0f).translationY(still ? 0f : 40 * density).setDuration(350).start();
        for (View v : new View[]{shelfView, heroName}) v.animate().alpha(0f).setDuration(350).start();
        backdropScrim.animate().alpha(0.75f).setDuration(350).start();
        bindFullShelf(false);
        fullShelf.setAlpha(0f);
        fullShelf.setVisibility(View.VISIBLE);
        fullShelf.animate().alpha(1f).setDuration(400).start();
        fsPlay.requestFocus();
    }

    private void exitFullShelf(boolean animate) {
        if (!fullShown) return;
        fullShown = false;
        skipTakeoverFor = fsIndex;   // don't take over again straight away on the same card
        long d = animate ? 300 : 0;
        fullShelf.animate().alpha(0f).setDuration(d).withEndAction(() -> {
            if (!fullShown) fullShelf.setVisibility(View.GONE);
        }).start();
        content.animate().alpha(1f).translationY(0f).setDuration(d).start();
        for (View v : new View[]{shelfView, heroName}) v.animate().alpha(1f).setDuration(d).start();
        backdropScrim.animate().alpha(1f).setDuration(d).start();
        shelf.focusIndex(fsIndex);
    }

    private void page(int step) {
        fsIndex = Math.max(0, Math.min(fsIndex + step, fsItems.size() - 1));
        bindFullShelf(true);
    }

    /** Title, episode, time left and description of the current item, and its artwork behind. */
    private void bindFullShelf(boolean fade) {
        TopShelf.Item it = fsItems.get(fsIndex);
        Runnable bind = () -> {
            CharSequence app = "";
            try {
                app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(it.pkg, 0));
            } catch (Exception ignored) {
            }
            fsApp.setText(app);
            fsTitle.setText(it.name != null ? it.name : it.title);
            StringBuilder sub = new StringBuilder();
            if (it.episode != null) sub.append(it.episode);
            if (it.progress > 0.02f && it.durationMs > 0) {
                long left = Math.max(1, (it.durationMs - it.positionMs) / 60_000L);
                if (sub.length() > 0) sub.append("  ·  ");
                sub.append(getString(R.string.minutes_left, (int) left));
            }
            fsSub.setText(sub);
            fsSub.setVisibility(sub.length() > 0 ? View.VISIBLE : View.GONE);
            fsDesc.setText(it.description);
            fsDesc.setVisibility(it.description == null || it.description.isEmpty() ? View.GONE : View.VISIBLE);
            fsPlay.setText(it.progress > 0.02f ? R.string.resume : R.string.play);
            fsDots.removeAllViews();
            if (fsItems.size() > 1) {
                for (int i = 0; i < fsItems.size(); i++) {
                    View dot = new View(this);
                    dot.setBackgroundResource(R.drawable.page_dot);
                    dot.setAlpha(i == fsIndex ? 1f : 0.3f);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (8 * density), (int) (8 * density));
                    lp.setMarginEnd((int) (8 * density));
                    fsDots.addView(dot, lp);
                }
            }
            showBackdrop(it);
        };
        if (!fade || Look.reduceMotion(this)) {
            bind.run();
            return;
        }
        fsTitle.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            bind.run();
            fsTitle.animate().alpha(1f).setDuration(250).start();
        }).start();
    }

    private void hideBackdrop() {
        if (!backdropShown) return;
        backdropShown = false;
        backdrop.animate().alpha(0f).setDuration(250).start();
        backdropScrim.animate().alpha(0f).setDuration(250).start();
    }

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        heroDockTop = dockTopInRoot;
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(heroY());
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(heroY());
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(heroY());
        }
        // The app's own Continue watching and previews, Apple TV's Top Shelf; the title moves up when it has any.
        AppAdapter.Entry e = adapter.entry(position);
        float bandTop = dockTopInRoot - 14 * density;
        String shelfFor = e != null && e.isApp() ? e.pkg : null;
        shelf.showFor(shelfFor, bandTop - 10 * density, visible -> {
            if (visible == shelfShown) return;
            shelfShown = visible;
            heroName.setPivotX(0f);
            heroName.setPivotY(heroName.getHeight());
            float s = visible ? 0.72f : 1f;
            heroName.animate().y(heroY()).scaleX(s).scaleY(s).setDuration(240).start();
        });
    }

    /** Where the top shelf title goes: above the dock, or above the shelf's cards when they're showing. */
    private float heroY() {
        float bandTop = heroDockTop - 14 * density;
        if (shelfShown) return bandTop - 10 * density - shelf.height() - 2 * density - heroName.getHeight();
        return bandTop - 26 * density - heroName.getHeight();
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        shelf.hide();
        if (shelfShown) {
            shelfShown = false;
            heroName.setScaleX(1f);
            heroName.setScaleY(1f);
        }
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileKey(int position, KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (isOk(keyCode)) {
            if (event.getRepeatCount() == 0) {
                pressBegunInMove = movingKey != null;
            } else {
                // OK held: remotes send repeats while it's down. Open the menu on the first one rather than only
                // on the view's long-press timer, which a quick release can beat.
                View card = cardFor(position);
                if (!pressBegunInMove && movingKey == null && card != null && !adapter.isHiddenTile(position)) {
                    onTileLongPressed(position, card);
                }
                return true;
            }
        }
        if (movingKey != null) return onMoveKey(position, keyCode);
        boolean arrow = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!arrow) return false;
        lastArrow = keyCode;
        lastArrowAt = SystemClock.uptimeMillis();
        int count = adapter.getItemCount();
        View card = cardFor(position);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Straight to the tile beside it, as Up and Down do: Android's focus finder can come up empty right
            // after the grid is rebuilt (a new folder, a hidden app). Row ends hold focus.
            int col = position % COLUMNS;
            boolean left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
            boolean edge = left ? col == 0 : col == COLUMNS - 1 || position == count - 1;
            if (edge) {
                if (card != null) nudge(card, keyCode);
            } else {
                focusTile(left ? position - 1 : position + 1);
            }
            return true;
        }
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS && shelf.hasItems()) {  // dock row with a top shelf: into its cards
                shelf.focusLast();                         // the card you were on, the first for a new app
                return true;
            }
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {                // already on the last row: stay put
                if (card != null) nudge(card, keyCode);
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    /**
     * The TV's navigation click. Android plays it only when it moves focus itself; arrows the launcher handles (the
     * tiles, the top bar, between the shelf and the dock) would be silent, while the Top Shelf's own Left and Right
     * click. So: an arrow we handled that moved focus gets the same click. One the launcher leaves to Android gets
     * Android's, never both.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        View before = getCurrentFocus();
        boolean handled = super.dispatchKeyEvent(event);
        if (handled && event.getAction() == KeyEvent.ACTION_DOWN && movingKey == null) {
            View now = getCurrentFocus();
            if (now != null && now != before) click(now, event.getKeyCode());
        }
        return handled;
    }

    static void click(View v, int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: v.playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: v.playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT); break;
            case KeyEvent.KEYCODE_DPAD_UP: v.playSoundEffect(SoundEffectConstants.NAVIGATION_UP); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: v.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN); break;
            default: break;
        }
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    // ---- arranging tiles -------------------------------------------------------------------------

    /**
     * OK held on a tile. Apps: Move, Add to / Remove from dock, Move to folder, Hide, App info.
     * Folders: Move, Add to / Remove from dock, Rename, Ungroup.
     */
    @Override
    public void onTileLongPressed(int position, View card) {
        openTileMenu(position, card, null);
    }

    /** The tile menu, starting on the row titled {@code focus} (back from Move to folder), or the first. */
    private void openTileMenu(int position, View card, CharSequence focus) {
        if (movingKey != null || (tileMenu != null && tileMenu.window.isShowing())) return;
        AppAdapter.Entry e = adapter.entry(position);
        if (e == null || e.isHiddenTile()) return;
        GlassPopup menu = new GlassPopup(this, root);
        tileMenu = menu;
        String key = e.key;
        menu.addRow(R.drawable.ic_move, getString(R.string.menu_move), () -> startMove(key));
        if (position >= COLUMNS) {
            menu.addRow(R.drawable.ic_dock_add, getString(R.string.menu_add_to_dock), () -> {
                adapter.moveToDock(adapter.positionOf(key));
                refocus(key);
            });
        } else if (adapter.appCount() > COLUMNS) {
            menu.addRow(R.drawable.ic_dock_remove, getString(R.string.menu_remove_from_dock), () -> {
                adapter.removeFromDock(adapter.positionOf(key));
                refocus(key);
            });
        }
        if (e.isFolder()) {
            menu.addRow(R.drawable.ic_edit, getString(R.string.menu_rename), () ->
                    GlassPopup.prompt(this, root, getString(R.string.folder_name), e.label, name -> {
                        adapter.renameFolder(key, name);
                        refocus(key);
                    }));
            menu.addRow(R.drawable.ic_dock_remove, getString(R.string.menu_ungroup), () -> {
                String first = e.members.get(0).key;
                adapter.ungroup(key);
                refocus(first);
            });
        } else {
            menu.addRow(R.drawable.ic_folder_add, getString(R.string.menu_move_to_folder),
                    () -> grid.post(() -> showMoveToFolder(key, card)));
            menu.addRow(R.drawable.ic_hidden, getString(R.string.menu_hide), () -> {
                int pos = adapter.positionOf(key);
                adapter.hide(pos);
                grid.post(() -> focusTile(Math.min(pos, adapter.getItemCount() - 1)));
            });
            menu.addRow(R.drawable.ic_info, getString(R.string.menu_app_info), () -> adapter.openAppInfo(e));
        }
        // The top shelf title would show through the menu; bring it back when the menu closes.
        hideHero();
        menu.window.setOnDismissListener(() -> grid.post(() -> {
            int pos = adapter.positionOf(key);
            if (movingKey == null && pos >= 0 && getCurrentFocus() != null
                    && grid.findContainingItemView(getCurrentFocus()) != null) {
                focusTile(pos);
            }
        }));
        menu.focusRow(focus);
        menu.showAt(card, (int) (210 * density));
    }

    /** "Move to folder": the existing folders, or a new one (named on the spot). */
    private void showMoveToFolder(String appKey, View card) {
        GlassPopup p = new GlassPopup(this, root);
        p.upTo(() -> {
            int pos = adapter.positionOf(appKey);
            View c = cardFor(pos);
            if (pos >= 0 && c != null) openTileMenu(pos, c, getString(R.string.menu_move_to_folder));
        });
        for (AppAdapter.Entry f : adapter.folderEntries()) {
            p.addRow(R.drawable.ic_folder, f.label, () -> {
                String folderKey = adapter.addToFolder(adapter.positionOf(appKey), f.key, null);
                refocus(folderKey);
            });
        }
        AppAdapter.Entry moving = adapter.entry(adapter.positionOf(appKey));
        String suggested = FolderNames.suggest(this,
                java.util.Collections.singletonList(moving != null ? moving.pkg : null));
        p.addRow(R.drawable.ic_add, getString(R.string.new_folder), () ->
                GlassPopup.prompt(this, root, getString(R.string.folder_name), suggested, name -> {
                            String folderKey = adapter.addToFolder(adapter.positionOf(appKey), null, name);
                            refocus(folderKey);
                        }));
        p.showAt(card, (int) (210 * density));
    }

    /** An open folder: its apps as small tiles, a row of {@link #COLUMNS} at a time, in the middle of the screen. */
    @Override
    public void onFolderClicked(int position, View card) {
        if (movingKey != null) return;
        AppAdapter.Entry folder = adapter.entry(position);
        if (folder == null || !folder.isFolder()) return;
        GlassPopup p = new GlassPopup(this, root);
        p.addTitle(folder.label);
        LinearLayout row = null;
        for (int i = 0; i < folder.members.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                p.addView(row);
            }
            row.addView(folderTile(p, folder, folder.members.get(i)));
        }
        hideHero();
        p.window.setOnDismissListener(() -> refocus(folder.key));
        p.showCentered();
    }

    private View folderTile(GlassPopup folderPopup, AppAdapter.Entry folder, AppAdapter.Entry app) {
        View v = getLayoutInflater().inflate(R.layout.item_folder_app, root, false);
        View card = v.findViewById(R.id.card);
        AppAdapter.roundCorners(card, 12 * density);
        AppAdapter.bindArt(app, v.findViewById(R.id.banner), v.findViewById(R.id.icon), v.findViewById(R.id.label));
        v.findViewById(R.id.label).setVisibility(View.GONE);   // the name is already under the card
        ((TextView) v.findViewById(R.id.name)).setText(app.label);
        card.setOnClickListener(x -> {
            folderPopup.window.dismiss();
            adapter.launch(app);
        });
        Runnable menu = () -> {
            if (tileMenu != null && tileMenu.window.isShowing()) return;   // timer and key repeat both fire
            GlassPopup m = new GlassPopup(this, root);
            tileMenu = m;
            m.addRow(R.drawable.ic_dock_remove, getString(R.string.menu_remove_from_folder), () -> {
                folderPopup.window.dismiss();
                adapter.removeFromFolder(folder.key, app.key);
                refocus(app.key);
            });
            m.addRow(R.drawable.ic_info, getString(R.string.menu_app_info), () -> adapter.openAppInfo(app));
            m.showAt(card, (int) (210 * density));
        };
        card.setOnLongClickListener(x -> {
            menu.run();
            return true;
        });
        card.setOnKeyListener((x, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && isOk(keyCode) && event.getRepeatCount() == 1) {
                menu.run();
                return true;
            }
            return event.getAction() == KeyEvent.ACTION_DOWN && isOk(keyCode) && event.getRepeatCount() > 1;
        });
        card.setOnFocusChangeListener((x, focused) -> x.animate().scaleX(focused ? 1.08f : 1f)
                .scaleY(focused ? 1.08f : 1f).setDuration(200).start());
        return v;
    }

    // ---- notifications --------------------------------------------------------------------------

    /** The bell: a red dot when there's something to read; hidden when the notification listener isn't on. */
    private void updateBell() {
        if (btnNotifications == null) return;
        if (!MediaWatcher.connected()) {
            if (btnNotifications.hasFocus()) focusTile(lastFocused);
            btnNotifications.setVisibility(View.GONE);
            return;
        }
        btnNotifications.setVisibility(View.VISIBLE);
        int n = MediaWatcher.visible().size();
        if (n > 0) {
            btnNotifications.setImageTintList(null);   // the dot keeps its red; the bell colours itself
            btnNotifications.setImageResource(R.drawable.ic_bell_dot);
            btnNotifications.setContentDescription(getString(R.string.notifications_count, n));
        } else {
            btnNotifications.setImageTintList(getColorStateList(R.drawable.pill_text));
            btnNotifications.setImageResource(R.drawable.ic_bell);
            btnNotifications.setContentDescription(getString(R.string.no_notifications));
        }
    }

    /** What Google TV's notification panel did: newest first; OK opens one, holding OK dismisses it. */
    private void showNotifications(View anchor) {
        List<android.service.notification.StatusBarNotification> list = MediaWatcher.visible();
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.no_notifications, Toast.LENGTH_SHORT).show();
            return;
        }
        GlassPopup p = new GlassPopup(this, root);
        p.addTitle(getString(R.string.notifications));
        int[] left = {0};
        // One held OK dismisses one notification: the long-press timer and the key repeat both report the hold,
        // and the second must not land on the next row once focus has moved there.
        boolean[] pressUsed = {false};
        // The panel scrolls, so show them all (within reason); Clear all clears every one either way.
        for (android.service.notification.StatusBarNotification sbn : list.subList(0, Math.min(50, list.size()))) {
            p.addView(notificationRow(p, sbn, left, pressUsed));
            left[0]++;
        }
        p.addSeparator();
        p.addRow(R.drawable.ic_delete, getString(R.string.clear_all), () -> {
            for (android.service.notification.StatusBarNotification sbn : list) MediaWatcher.dismiss(sbn);
        });
        p.addTitle(getString(R.string.notification_hint));
        p.showAt(anchor, (int) (460 * density));
    }

    private View notificationRow(GlassPopup popup, android.service.notification.StatusBarNotification sbn, int[] left,
                                 boolean[] pressUsed) {
        View row = getLayoutInflater().inflate(R.layout.item_notification, popup.rows, false);
        android.content.pm.PackageManager pm = getPackageManager();
        CharSequence app = sbn.getPackageName();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(sbn.getPackageName(), 0);
            app = pm.getApplicationLabel(ai);
            ((ImageView) row.findViewById(R.id.nIcon)).setImageDrawable(pm.getApplicationIcon(ai));
        } catch (Exception ignored) {
        }
        CharSequence title = MediaWatcher.title(sbn), text = MediaWatcher.text(sbn);
        ((TextView) row.findViewById(R.id.nTitle)).setText(title != null && title.length() > 0 ? title : app);
        TextView body = row.findViewById(R.id.nText);
        body.setText(text);
        body.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        long now = System.currentTimeMillis();
        ((TextView) row.findViewById(R.id.nTime)).setText(now - sbn.getPostTime() < 60_000 ? getString(R.string.just_now)
                : android.text.format.DateUtils.getRelativeTimeSpanString(sbn.getPostTime(), now,
                        android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE));
        row.setOnClickListener(v -> {
            popup.window.dismiss();
            openNotification(sbn);
        });
        boolean[] gone = {false};
        Runnable dismiss = () -> {
            if (gone[0] || pressUsed[0]) return;
            gone[0] = true;
            pressUsed[0] = true;
            MediaWatcher.dismiss(sbn);
            int i = popup.rows.indexOfChild(row);
            popup.rows.removeView(row);
            if (--left[0] <= 0) {
                popup.window.dismiss();
                return;
            }
            View next = popup.rows.getChildAt(Math.min(i, popup.rows.getChildCount() - 1));
            if (next != null) next.requestFocus();
        };
        row.setOnLongClickListener(v -> {
            dismiss.run();
            return true;
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || !isOk(keyCode)) return false;
            if (event.getRepeatCount() == 0) {
                pressUsed[0] = false;   // a new press
                return false;
            }
            if (event.getRepeatCount() == 1) dismiss.run();   // OK held (remotes repeat while it's down)
            return true;
        });
        return row;
    }

    /** Does what tapping it on a phone would: its own action, else the app. Auto-cancel ones go away. */
    private void openNotification(android.service.notification.StatusBarNotification sbn) {
        android.app.Notification n = sbn.getNotification();
        boolean opened = false;
        if (n.contentIntent != null) {
            try {
                n.contentIntent.send();
                opened = true;
            } catch (Exception ignored) {
            }
        }
        if (!opened) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(sbn.getPackageName());
            if (i != null) open(i);
        }
        if ((n.flags & android.app.Notification.FLAG_AUTO_CANCEL) != 0 || !opened) MediaWatcher.dismiss(sbn);
    }

    /**
     * Apple TV's App Switcher: the apps used last, newest first, as a row of cards over Home. OK opens one; Up or
     * Back closes it. (Apple opens it with a double-press of the TV button; Android doesn't let a launcher catch
     * that on Home, hence the button.)
     */
    private void showRecents() {
        List<String> pkgs = Recents.recent(this, 12);
        if (pkgs.isEmpty()) {
            Toast.makeText(this, R.string.recent_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.pm.PackageManager pm = getPackageManager();
        GlassPopup p = new GlassPopup(this, root);
        p.addTitle(getString(R.string.recent));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (String pkg : pkgs) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                android.graphics.drawable.Drawable banner = pm.getApplicationBanner(ai);
                AppAdapter.Entry e = new AppAdapter.Entry(String.valueOf(pm.getApplicationLabel(ai)),
                        pm.getLeanbackLaunchIntentForPackage(pkg).getComponent(), banner, pm.getApplicationIcon(ai));
                row.addView(recentCard(p, e));
            } catch (Exception ignored) {
            }
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setFocusable(false);   // a scroll view takes focus itself; start on the most recent app instead
        scroll.addView(row);
        p.addView(scroll);
        p.showCentered();
    }

    private View recentCard(GlassPopup popup, AppAdapter.Entry app) {
        View v = getLayoutInflater().inflate(R.layout.item_folder_app, root, false);
        View card = v.findViewById(R.id.card);
        AppAdapter.roundCorners(card, 12 * density);
        AppAdapter.bindArt(app, v.findViewById(R.id.banner), v.findViewById(R.id.icon), v.findViewById(R.id.label));
        v.findViewById(R.id.label).setVisibility(View.GONE);   // the name is already under the card
        ((TextView) v.findViewById(R.id.name)).setText(app.label);
        card.setOnClickListener(x -> {
            popup.window.dismiss();
            adapter.launch(app);
        });
        card.setOnFocusChangeListener((x, focused) -> x.animate().scaleX(focused ? 1.08f : 1f)
                .scaleY(focused ? 1.08f : 1f).setDuration(200).start());
        return v;
    }

    /** The "Hidden apps" tile: pick an app to show it again. */
    @Override
    public void onHiddenTileClicked(View card) {
        if (movingKey != null) return;
        GlassPopup p = new GlassPopup(this, root);
        for (AppAdapter.Entry e : adapter.hiddenApps()) {
            final String key = e.key;
            p.addRow(e.icon, !e.isApp(), e.label, null, false, true, () -> {   // tint the built-in tiles' icons
                adapter.unhide(key);
                refocus(key);
            });
        }
        p.showAt(card, (int) (220 * density));
    }

    private void startMove(String key) {
        View card = cardFor(adapter.positionOf(key));
        if (card == null) return;
        movingKey = key;
        moveSnapshot = adapter.orderSnapshot();
        hideHero();
        raise(card, true);
        if (!Look.reduceMotion(this)) grid.setItemAnimator(slideAnimator());
        // A gentle wiggle while the tile is picked up, as on Apple TV; with Reduce Motion it just dims a little.
        if (Look.reduceMotion(this)) {
            card.setAlpha(0.8f);
            Toast.makeText(this, R.string.move_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        wiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -1.4f, 1.4f);
        wiggle.setDuration(140);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.start();
        Toast.makeText(this, R.string.move_hint, Toast.LENGTH_SHORT).show();
    }

    /**
     * Arrows push the picked-up tile one slot at a time through the dock and grid, as on Apple TV; OK places it, Back
     * puts it back. Folders are made from the tile menu (Move to folder), not by dropping one app on another.
     */
    private boolean onMoveKey(int position, int keyCode) {
        int last = adapter.appCount() - 1;   // never past the "Hidden apps" tile
        int target = position;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: target = position - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: target = position + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: target = position - COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: target = Math.min(position + COLUMNS, last); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                endMove(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                endMove(false);
                return true;
            default:
                return true;   // nothing else while moving
        }
        if (target < 0 || target > last || target == position) {
            View card = cardFor(position);
            if (card != null) nudge(card, keyCode);
            return true;
        }
        adapter.move(position, target);
        followMovedTile();
        View moved = cardFor(position);
        if (moved != null) click(moved, keyCode);
        return true;
    }

    /** The carried tile draws above its neighbours (RecyclerView otherwise draws in adapter order). */
    private void raise(View card, boolean up) {
        View item = (View) card.getParent();
        if (item != null) item.setTranslationZ(up ? 24 * density : 0f);
    }

    /**
     * The moved tile keeps focus, so no focus change fires: once the grid has placed it (just before the next
     * frame), move the glass, the scroll and the top shelf as if it had just been focused there.
     */
    private void followMovedTile() {
        grid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                grid.getViewTreeObserver().removeOnPreDrawListener(this);
                int pos = adapter.positionOf(movingKey);
                View card = cardFor(pos);
                if (movingKey != null && card != null) {
                    raise(card, true);
                    onTileFocused(pos, card);
                }
                return true;
            }
        });
    }

    /**
     * While a tile is carried, the tiles it passes glide into their new slots instead of jumping. Only moves animate:
     * the rebind of shifted tiles (dock tiles hide their names) happens in place, with no cross-fade.
     */
    private RecyclerView.ItemAnimator slideAnimator() {
        DefaultItemAnimator a = new DefaultItemAnimator();
        a.setSupportsChangeAnimations(false);
        a.setMoveDuration(240);
        a.setAddDuration(0);
        a.setRemoveDuration(0);
        return a;
    }

    /** Back to no item animations once the last slide has finished (other grid updates rebuild it instantly). */
    private void stopSliding() {
        RecyclerView.ItemAnimator a = grid.getItemAnimator();
        if (a == null) return;
        a.isRunning(() -> grid.post(() -> {
            if (movingKey == null) grid.setItemAnimator(null);
        }));
    }

    private void lowerAll() {
        for (int i = 0; i < grid.getChildCount(); i++) grid.getChildAt(i).setTranslationZ(0f);
    }

    private void endMove(boolean keep) {
        lowerAll();
        stopSliding();
        String key = movingKey;
        movingKey = null;
        View moved = cardFor(adapter.positionOf(key));
        if (moved != null) moved.setAlpha(1f);
        if (wiggle != null) {
            wiggle.cancel();
            if (moved != null) moved.setRotation(0f);
            wiggle = null;
        }
        if (keep) adapter.save();
        else adapter.restoreOrder(moveSnapshot);
        moveSnapshot = null;
        refocus(key);
    }

    /** Focuses a tile by key once the grid has laid out its new arrangement (after reload(), before the next frame). */
    private void refocus(String key) {
        grid.post(() -> {
            if (!grid.hasPendingAdapterUpdates() && !grid.isLayoutRequested()) {
                focusKey(key);
                return;
            }
            grid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    grid.getViewTreeObserver().removeOnPreDrawListener(this);
                    focusKey(key);
                    return true;
                }
            });
        });
    }

    private void focusKey(String key) {
        int pos = adapter.positionOf(key);
        if (pos >= 0) focusTile(pos);
    }

    private View cardFor(int position) {
        RecyclerView.ViewHolder vh = position >= 0 ? grid.findViewHolderForAdapterPosition(position) : null;
        return vh != null ? vh.itemView.findViewById(R.id.card) : null;
    }

    // ---- quick panel (Apple TV's Control Center) and profiles -----------------------------------

    /**
     * Profile, Do Not Disturb and shortcuts into the TV's own settings. Turning the TV off or a sleep timer needs
     * system permissions a sideloaded app can't get, so those stay in the TV's settings.
     */
    private void showQuickPanel(View anchor) {
        showQuickPanel(anchor, null);
    }

    /** Controls, starting on the row titled {@code focus} (coming back from one of its sub-menus), or null. */
    private void showQuickPanel(View anchor, CharSequence focus) {
        GlassPopup p = new GlassPopup(this, root);
        View play = addNowPlaying(p);
        p.addRow(getDrawable(R.drawable.ic_profile), true, layout.profileName(layout.currentProfile()),
                getString(R.string.profiles), false, true, () -> grid.post(() -> showProfiles(anchor, true)));
        p.addSeparator();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.isNotificationPolicyAccessGranted()) {
            View[] row = new View[1];
            row[0] = p.addRow(getDrawable(R.drawable.ic_dnd), true, getString(R.string.dnd), dndLabel(nm),
                    false, false, () -> {
                        boolean on = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
                        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL
                                : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                        GlassPopup.setStatus(row[0], dndLabel(nm), false);
                    });
        }
        p.addRow(getDrawable(weather.hasCity() ? weather.icon() : R.drawable.ic_w_cloud), true,
                getString(R.string.weather), weather.hasCity() ? weather.city() : getString(R.string.set_city), false, true,
                () -> {
                    if (weather.hasCity()) grid.post(() -> showWeatherMenu(anchor));
                    else GlassPopup.prompt(this, root, getString(R.string.weather_city), "", this::setWeatherCity);
                });
        p.addRow(getDrawable(R.drawable.ic_columns), true, getString(R.string.home_layout), null, false, true,
                () -> grid.post(() -> showLayoutMenu(anchor)));
        p.addRow(getDrawable(R.drawable.ic_display), true, getString(R.string.display), lookLabel(), false, true,
                () -> grid.post(() -> showDisplayMenu(anchor)));
        // Wi-Fi has its own button in the top bar, so it's not repeated here.
        addSettingsRow(p, R.drawable.ic_sound, R.string.sound_output, new Intent(Settings.ACTION_SOUND_SETTINGS));
        p.addRow(getDrawable(R.drawable.ic_more), true, getString(R.string.more_settings), null, false, true,
                () -> grid.post(() -> showMoreSettings(anchor)));
        // The TV's own settings (this used to be a top-bar button of its own).
        p.addSeparator();
        p.addRow(R.drawable.ic_settings, getString(R.string.settings), () -> open(new Intent(Settings.ACTION_SETTINGS)));
        p.focusRow(focus);
        p.showAt(anchor, (int) (260 * density));
        // Something's playing: start on play/pause, as on Apple TV (unless coming back from a sub-menu).
        if (play != null && focus == null) play.requestFocus();
    }

    /** Back to Controls, onto the row that opened the sub-menu. */
    private Runnable backToControls(View anchor, CharSequence row) {
        return () -> showQuickPanel(anchor, row);
    }

    /**
     * Now Playing at the top of Controls, when an app has a media session: artwork, title, and previous /
     * play-pause / next, kept current while the panel is open. Returns the play/pause button, or null.
     */
    private View addNowPlaying(GlassPopup p) {
        android.media.session.MediaController c = NowPlaying.current(this);
        if (c == null) return null;
        View card = getLayoutInflater().inflate(R.layout.item_now_playing, p.rows, false);
        ImageView art = card.findViewById(R.id.npArt);
        TextView title = card.findViewById(R.id.npTitle);
        TextView subtitle = card.findViewById(R.id.npSubtitle);
        ImageView play = card.findViewById(R.id.npPlay);
        AppAdapter.roundCorners(art, 10 * density);
        Runnable refresh = () -> {
            NowPlaying.showArt(this, shelf.images(), art, c);
            title.setText(NowPlaying.title(c));
            String sub = NowPlaying.subtitle(c);
            subtitle.setText(sub);
            subtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            play.setImageResource(NowPlaying.isPlaying(c) ? R.drawable.ic_pause : R.drawable.ic_play);
        };
        refresh.run();
        android.media.session.MediaController.TransportControls t = c.getTransportControls();
        card.findViewById(R.id.npPrev).setOnClickListener(v -> t.skipToPrevious());
        card.findViewById(R.id.npNext).setOnClickListener(v -> t.skipToNext());
        play.setOnClickListener(v -> {
            if (NowPlaying.isPlaying(c)) t.pause();
            else t.play();
        });
        android.media.session.MediaController.Callback cb = new android.media.session.MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
                refresh.run();
            }

            @Override
            public void onMetadataChanged(android.media.MediaMetadata metadata) {
                refresh.run();
            }
        };
        c.registerCallback(cb, handler);
        p.window.setOnDismissListener(() -> c.unregisterCallback(cb));
        p.addView(card);
        p.addSeparator();
        return play;
    }

    // ---- Now Playing pill --------------------------------------------------------------------------

    /** While Home is on screen, follow media sessions: one starting, stopping or changing track updates the pill. */
    private void startNowPlaying() {
        android.media.session.MediaSessionManager msm = getSystemService(android.media.session.MediaSessionManager.class);
        if (msm != null && npSessions == null) {
            npSessions = controllers -> updateNowPlaying();
            try {
                msm.addOnActiveSessionsChangedListener(npSessions, new ComponentName(this, MediaWatcher.class), handler);
            } catch (SecurityException e) {
                npSessions = null;   // no notification access: the pill stays hidden
            }
        }
        updateNowPlaying();
    }

    private void stopNowPlaying() {
        android.media.session.MediaSessionManager msm = getSystemService(android.media.session.MediaSessionManager.class);
        if (msm != null && npSessions != null) msm.removeOnActiveSessionsChangedListener(npSessions);
        npSessions = null;
        npHideAt = 0;
        follow(null);
        handler.removeCallbacks(npRecheck);
        ((PlayingBars) findViewById(R.id.npPillBars)).setPlaying(false);
    }

    /**
     * The pill shows while something plays, and for 10 minutes after it's paused (to resume from); then it hides,
     * as on Apple TV, where a long-paused session no longer counts as playing.
     */
    private void updateNowPlaying() {
        handler.removeCallbacks(npRecheck);
        android.media.session.MediaController c = NowPlaying.current(this);
        boolean playing = c != null && NowPlaying.isPlaying(c);
        boolean show = c != null && (playing || NowPlaying.recentlyPaused(c));
        if (!show && btnNowPlaying.getVisibility() == View.VISIBLE) {
            // A pause or a skip passes through moments with nothing to show (stopped, no track yet): hide only
            // when there's still nothing three seconds later, so the pill doesn't blink off and on.
            long now = SystemClock.uptimeMillis();
            if (npHideAt == 0) npHideAt = now + 3000;
            if (now < npHideAt) {
                if (c != null) follow(c);
                handler.postDelayed(npRecheck, npHideAt - now);
                return;
            }
        }
        npHideAt = 0;
        follow(show ? c : null);
        if (!show) {
            if (btnNowPlaying.hasFocus()) {
                View next = neighbourButton(btnNowPlaying, 1);
                if (next != null) next.requestFocus();
                else focusTile(lastFocused);
            }
            btnNowPlaying.setVisibility(View.GONE);
            return;
        }
        NowPlaying.showArt(this, shelf.images(), findViewById(R.id.npPillArt), c);
        String title = NowPlaying.title(c);
        ((TextView) findViewById(R.id.npPillTitle)).setText(title.isEmpty() ? NowPlaying.appName(this, c) : title);
        ((PlayingBars) findViewById(R.id.npPillBars)).setPlaying(playing);
        btnNowPlaying.setVisibility(View.VISIBLE);
        if (!playing) handler.postDelayed(npRecheck, 60_000L);   // hide once the pause is 10 minutes old
    }

    /** Listen to this session's play/pause and track changes (and stop listening to the previous one). */
    private void follow(android.media.session.MediaController c) {
        if (npController != null && c != null && npController.getSessionToken().equals(c.getSessionToken())) return;
        if (npController != null && npCallback != null) npController.unregisterCallback(npCallback);
        npController = c;
        npCallback = null;
        if (c == null) return;
        npCallback = new android.media.session.MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
                updateNowPlaying();
            }

            @Override
            public void onMetadataChanged(android.media.MediaMetadata metadata) {
                updateNowPlaying();
            }

            @Override
            public void onSessionDestroyed() {
                updateNowPlaying();
            }
        };
        c.registerCallback(npCallback, handler);
    }

    /** OK on the pill: the player under it, starting on play/pause, with a row to open the app. */
    private void showPlayer(View anchor) {
        android.media.session.MediaController c = npController;
        if (c == null) return;
        GlassPopup p = new GlassPopup(this, root);
        View play = addNowPlaying(p);
        if (play == null) return;
        Drawable icon;
        try {
            icon = getPackageManager().getApplicationIcon(c.getPackageName());
        } catch (Exception e) {
            icon = getDrawable(R.drawable.ic_play);
        }
        p.addRow(icon, false, getString(R.string.open_app, NowPlaying.appName(this, c)), null, false, true,
                () -> NowPlaying.open(this, c));
        p.showAt(anchor, (int) (380 * density));
        play.requestFocus();
    }

    /** Shortcuts into the TV's own settings that don't need a row of their own. */
    private void showMoreSettings(View anchor) {
        GlassPopup p = new GlassPopup(this, root);
        p.upTo(backToControls(anchor, getString(R.string.more_settings)));
        addSettingsRow(p, R.drawable.ic_bluetooth, R.string.bluetooth, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        addSettingsRow(p, R.drawable.ic_display, R.string.picture, new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        addSettingsRow(p, R.drawable.ic_photo, R.string.screensaver, new Intent(Settings.ACTION_DREAM_SETTINGS));
        // SystemUI's "start the screensaver now" activity, where the TV ships it.
        addSettingsRow(p, R.drawable.ic_screensaver_now, R.string.start_screensaver,
                new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator"));
        addSettingsRow(p, R.drawable.ic_accessibility, R.string.accessibility,
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        String stock = stockLauncher();
        if (stock != null) {
            p.addSeparator();
            p.addRow(R.drawable.ic_livetv, getString(R.string.restore_stock_home, appLabel(stock)),
                    () -> grid.post(() -> confirmStockHome(anchor, stock)));
        }
        p.showAt(anchor, (int) (260 * density));
    }

    private CharSequence appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** The TV's own home screen app, which the installer switched off so this one could be the home screen. */
    private String stockLauncher() {
        for (String pkg : new String[]{"com.google.android.apps.tv.launcherx",   // Google TV
                "com.google.android.tvlauncher", "com.google.android.leanbacklauncher"}) {   // Android TV
            try {
                getPackageManager().getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                return pkg;
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /**
     * Back to the factory home screen. An app can't switch another app back on, but the TV's Settings can: this
     * opens the stock launcher's page there, where Enable brings it straight back (on Google TV it then takes Home
     * over from this one). Coming back here needs the installer again: Settings offers no Disable for it.
     */
    private void confirmStockHome(View anchor, String pkg) {
        GlassPopup p = new GlassPopup(this, root);
        p.upTo(() -> showMoreSettings(anchor));
        p.addTitle(getString(R.string.restore_stock_home, appLabel(pkg)));
        p.addNote(getString(R.string.restore_stock_home_note, appLabel(pkg)));
        p.addRow(R.drawable.ic_restore, getString(R.string.open_in_settings), () -> open(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg))));
        p.addRow(R.drawable.ic_check, getString(R.string.cancel), () -> { });
        p.showAt(anchor, (int) (260 * density));
    }

    /** Home screen layout: tiles per row, and backing up or restoring the arrangement. */
    private void showLayoutMenu(View anchor) {
        showLayoutMenu(anchor, null);
    }

    private void showLayoutMenu(View anchor, CharSequence focus) {
        GlassPopup p = new GlassPopup(this, root);
        p.upTo(backToControls(anchor, getString(R.string.home_layout)));
        p.focusRow(focus);
        p.addTitle(getString(R.string.home_layout));
        p.addRow(getDrawable(R.drawable.ic_columns), true, getString(R.string.tiles_per_row), String.valueOf(COLUMNS),
                false, true, () -> grid.post(() -> showColumns(anchor)));
        p.addSeparator();
        p.addRow(R.drawable.ic_backup, getString(R.string.backup_layout), () -> {
            java.io.File f = LayoutBackup.save(this);
            Toast.makeText(this, f != null ? getString(R.string.backup_saved, "Downloads/" + LayoutBackup.FILE)
                    : getString(R.string.backup_failed), Toast.LENGTH_LONG).show();
        });
        java.io.File found = LayoutBackup.find(this);
        if (found != null) {
            p.addRow(getDrawable(R.drawable.ic_restore), true, getString(R.string.restore_layout),
                    found.getPath().contains("/Android/data/")
                            ? (found.getPath().startsWith("/storage/emulated/") ? "TV" : "USB")   // this app's folder
                            : found.getParentFile() != null ? found.getParentFile().getName() : null, false, true,
                    () -> grid.post(() -> confirmRestore(anchor, found)));
        }
        p.showAt(anchor, (int) (260 * density));
    }

    /** Restoring replaces every profile's arrangement, so ask first. */
    private void confirmRestore(View anchor, java.io.File file) {
        GlassPopup p = new GlassPopup(this, root);
        p.upTo(() -> showLayoutMenu(anchor, getString(R.string.restore_layout)));
        p.addTitle(getString(R.string.restore_layout_question));
        p.addRow(R.drawable.ic_restore, getString(R.string.restore_layout), () -> {
            if (LayoutBackup.restore(this, file)) {
                Toast.makeText(this, R.string.restored, Toast.LENGTH_SHORT).show();
                lastFocused = 0;
                lastFocusedKey = null;
                recreate();   // profiles, tiles per row and display settings all come back at once
            } else {
                Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_LONG).show();
            }
        });
        p.addRow(R.drawable.ic_check, getString(R.string.cancel), () -> { });
        p.showAt(anchor, (int) (260 * density));
    }

    private String lookLabel() {
        int m = Look.mode(this);
        return getString(m == Look.LIGHT ? R.string.appearance_light : m == Look.AUTO ? R.string.appearance_auto
                : R.string.appearance_dark);
    }

    /** Appearance (Dark, Light, Automatic), Larger text and Reduce Motion, as in Apple TV's settings. */
    private void showDisplayMenu(View anchor) {
        GlassPopup p = new GlassPopup(this, root);
        p.upTo(backToControls(anchor, getString(R.string.display)));
        p.addTitle(getString(R.string.display));
        int[] modes = {Look.DARK, Look.LIGHT, Look.AUTO};
        int[] names = {R.string.appearance_dark, R.string.appearance_light, R.string.appearance_auto};
        for (int i = 0; i < modes.length; i++) {
            int mode = modes[i];
            p.addRow(getDrawable(R.drawable.ic_display), true, getString(names[i]), null, Look.mode(this) == mode,
                    true, () -> {
                        if (Look.mode(this) == mode) return;
                        Look.setMode(this, mode);
                        if (Look.light(this) != createdLight) recreate();
                    });
        }
        p.addSeparator();
        p.addRow(getDrawable(R.drawable.ic_text_size), true, getString(R.string.larger_text),
                getString(Look.largeText(this) ? R.string.on : R.string.off), false, true, () -> {
                    Look.setLargeText(this, !Look.largeText(this));
                    recreate();
                });
        View[] motion = new View[1];
        motion[0] = p.addRow(getDrawable(R.drawable.ic_motion), true, getString(R.string.reduce_motion),
                getString(Look.reduceMotionSetting(this) ? R.string.on : R.string.off), false, false, () -> {
                    Look.setReduceMotion(this, !Look.reduceMotionSetting(this));
                    GlassPopup.setStatus(motion[0], getString(Look.reduceMotionSetting(this) ? R.string.on
                            : R.string.off), false);
                });
        p.showAt(anchor, (int) (260 * density));
    }

    private String dndLabel(NotificationManager nm) {
        boolean on = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
        return getString(on ? R.string.on : R.string.off);
    }

    /** A quick panel row, only when this TV has a screen for the intent. */
    private void addSettingsRow(GlassPopup p, int icon, int title, Intent intent) {
        if (intent.resolveActivity(getPackageManager()) == null) return;
        p.addRow(icon, getString(title), () -> open(intent));
    }

    /** 4, 5 or 6 tiles per row (Apple TV shows 5 up to tvOS 16 and 6 since tvOS 17); the dock follows. */
    private void showColumns(View anchor) {
        GlassPopup p = new GlassPopup(this, root);
        p.upTo(() -> showLayoutMenu(anchor, getString(R.string.tiles_per_row)));
        p.addTitle(getString(R.string.tiles_per_row));
        for (int n = 4; n <= 6; n++) {
            final int cols = n;
            p.addRow(getDrawable(R.drawable.ic_columns), true, String.valueOf(n), null, n == COLUMNS, true, () -> {
                if (cols == COLUMNS) return;
                HomeLayout.setColumns(this, cols);
                recreate();   // rebuilds the grid, the dock and the tile sizes for the new count
            });
        }
        p.showAt(anchor, (int) (200 * density));
    }

    /** Pick, add, rename or delete a profile; each profile keeps its own dock, order, folders and hidden apps. */
    private void showProfiles(View anchor, boolean fromControls) {
        showProfiles(anchor, fromControls, null);
    }

    private void showProfiles(View anchor, boolean fromControls, CharSequence focus) {
        GlassPopup p = new GlassPopup(this, root);
        if (fromControls) p.upTo(backToControls(anchor, layout.profileName(layout.currentProfile())));
        p.focusRow(focus);
        p.addTitle(getString(R.string.profiles));
        String current = layout.currentProfile();
        for (String id : layout.profileIds()) {
            p.addRow(getDrawable(R.drawable.ic_profile), true, layout.profileName(id), null, id.equals(current),
                    true, () -> switchProfile(id));
        }
        p.addSeparator();
        p.addRow(R.drawable.ic_add, getString(R.string.add_profile), () ->
                GlassPopup.prompt(this, root, getString(R.string.profile_name),
                        getString(R.string.profile_n, layout.profileIds().size() + 1), name -> {
                            String id = layout.addProfile();
                            layout.renameProfile(id, name);
                            switchProfile(id);
                        }));
        String name = layout.profileName(current);
        p.addRow(R.drawable.ic_edit, getString(R.string.rename_profile, name), () ->
                GlassPopup.prompt(this, root, getString(R.string.profile_name), name, newName -> {
                    layout.renameProfile(current, newName);
                    updateGreeting();
                }));
        if (layout.hasSeveralProfiles()) {
            p.addRow(R.drawable.ic_delete, getString(R.string.delete_profile, name), () -> grid.post(() -> {
                GlassPopup confirm = new GlassPopup(this, root);
                confirm.upTo(() -> showProfiles(anchor, fromControls, getString(R.string.delete_profile, name)));
                confirm.addRow(R.drawable.ic_delete, getString(R.string.delete_profile, name),
                        () -> switchProfileAfter(() -> layout.deleteProfile(current)));
                confirm.addRow(R.drawable.ic_check, getString(R.string.cancel), () -> { });
                confirm.showAt(anchor, (int) (240 * density));
            }));
            View[] row = new View[1];
            row[0] = p.addRow(getDrawable(R.drawable.ic_profile), true, getString(R.string.ask_on_wake),
                    getString(layout.askOnWake() ? R.string.on : R.string.off), false, false, () -> {
                        layout.setAskOnWake(!layout.askOnWake());
                        GlassPopup.setStatus(row[0], getString(layout.askOnWake() ? R.string.on : R.string.off),
                                false);
                    });
        }
        p.showAt(anchor, (int) (260 * density));
    }

    private void switchProfile(String id) {
        switchProfileAfter(() -> layout.switchTo(id));
    }

    private void switchProfileAfter(Runnable change) {
        change.run();
        lastFocused = 0;
        lastFocusedKey = null;
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        grid.post(() -> focusTile(0));
    }

    /** The system search or Assistant, whichever this TV has; null hides the Search button. */
    private Intent findSearch() {
        Intent[] candidates = {
                new Intent(Intent.ACTION_ASSIST),
                new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH),
                new Intent(Intent.ACTION_SEARCH_LONG_PRESS),
                new Intent(Intent.ACTION_WEB_SEARCH),
        };
        for (Intent i : candidates) {
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(i, 0)) {
                // Android TV ships placeholder handlers that only say "You don't have an app that can do this".
                if (ri.activityInfo == null || ri.activityInfo.packageName.contains("frameworkpackagestubs")) continue;
                return i.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
        }
        return null;
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Weather options once a city is set: change it, °C or °F, or turn the weather off. */
    private void showWeatherMenu(View anchor) {
        GlassPopup p = new GlassPopup(this, root);
        p.upTo(backToControls(anchor, getString(R.string.weather)));
        p.addTitle(getString(R.string.weather));
        p.addRow(getDrawable(R.drawable.ic_edit), true, getString(R.string.weather_city), weather.cityLabel(), false, true,
                () -> GlassPopup.prompt(this, root, getString(R.string.weather_city), weather.city(), this::setWeatherCity));
        View[] unit = new View[1];
        unit[0] = p.addRow(getDrawable(R.drawable.ic_w_sun), true, getString(R.string.temperature_unit),
                weather.fahrenheit() ? "°F" : "°C", false, false, () -> {
                    weather.setFahrenheit(!weather.fahrenheit(), this::updateWeather);
                    GlassPopup.setStatus(unit[0], weather.fahrenheit() ? "°F" : "°C", false);
                });
        p.addRow(R.drawable.ic_delete, getString(R.string.weather_off), () -> weather.clearCity(this::updateWeather));
        p.showAt(anchor, (int) (300 * density));
    }

    /** Looks the name up; one match is used straight away, several (two Lisbons) are offered to choose from. */
    private void setWeatherCity(String name) {
        if (name.isEmpty()) return;
        weather.search(name, (result, places) -> {
            if (result == Weather.CitySearch.NOT_FOUND) {
                Toast.makeText(this, getString(R.string.city_not_found, name), Toast.LENGTH_LONG).show();
            } else if (result == Weather.CitySearch.OFFLINE) {
                Toast.makeText(this, R.string.no_connection, Toast.LENGTH_LONG).show();
            } else if (places.size() == 1) {
                weather.useCity(places.get(0), this::updateWeather);
            } else {
                GlassPopup p = new GlassPopup(this, root);
                p.addTitle(getString(R.string.choose_city));
                for (Weather.Place place : places) {
                    p.addRow(getDrawable(R.drawable.ic_place), true, place.title(),
                            place.area.isEmpty() ? null : place.area, false, true,
                            () -> weather.useCity(place, this::updateWeather));
                }
                p.showCentered();
            }
        });
    }

    /** The weather next to the greeting, or nothing when there's no city or no recent reading. */
    private void updateWeather() {
        String label = weather.label();
        if (label == null) {
            weatherBox.setVisibility(View.GONE);
            return;
        }
        weatherIcon.setImageResource(weather.icon());
        weatherText.setText(label);
        weatherBox.setVisibility(View.VISIBLE);
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        // With more than one profile, greet the one in use: "Good evening, Anna".
        if (layout.hasSeveralProfiles()) {
            greeting.setText(getString(R.string.greeting_with_name, getString(res),
                    layout.profileName(layout.currentProfile())));
        } else {
            greeting.setText(res);
        }
    }

    /**
     * The network button shows the connection, like the Mac menu bar: Ethernet (cable), Wi-Fi with as many bright
     * arcs as the signal is strong, arcs lighting up one after another while joining a network or getting an
     * address, dim arcs with "!" when connected but the internet isn't reachable, and crossed out when offline.
     * The description (for screen readers) carries the network name when we can read it.
     */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        int icon;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            boolean online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            // Android checks for internet a moment after a network comes up; until then it's still "connecting".
            boolean checking = caps != null && !online && SystemClock.uptimeMillis() - connectedAt < 10_000;
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(online ? R.string.ethernet : R.string.no_internet);
                icon = R.drawable.ic_ethernet;
            } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
                if (online) {
                    int level = info != null ? WifiManager.calculateSignalLevel(info.getRssi(), 3) : 2;
                    icon = level >= 2 ? R.drawable.ic_wifi_3 : level == 1 ? R.drawable.ic_wifi_2 : R.drawable.ic_wifi_1;
                } else if (checking) {
                    icon = R.drawable.anim_wifi_connecting;
                    text = getString(R.string.connecting);
                } else {
                    icon = R.drawable.ic_wifi_no_internet;
                    text = getString(R.string.no_internet);
                }
            } else if (wm != null && wm.isWifiEnabled() && info != null && isJoining(info.getSupplicantState())) {
                icon = R.drawable.anim_wifi_connecting;   // joining a network or getting an address
                text = getString(R.string.connecting);
            } else {
                text = getString(R.string.offline);
                icon = R.drawable.ic_wifi_off;
            }
        } catch (Exception e) {
            icon = R.drawable.ic_wifi;
        }
        btnWifi.setContentDescription(text);
        if (icon == wifiIcon) return;
        wifiIcon = icon;
        if (icon == R.drawable.anim_wifi_connecting && Look.reduceMotion(this)) {
            btnWifi.setImageResource(R.drawable.ic_wifi_connecting_0);   // Reduce Motion: still, dot only
            return;
        }
        btnWifi.setImageResource(icon);
        if (btnWifi.getDrawable() instanceof android.graphics.drawable.AnimationDrawable) {
            ((android.graphics.drawable.AnimationDrawable) btnWifi.getDrawable()).start();
        }
    }

    private static boolean isJoining(android.net.wifi.SupplicantState s) {
        switch (s) {
            case AUTHENTICATING:
            case ASSOCIATING:
            case ASSOCIATED:
            case FOUR_WAY_HANDSHAKE:
            case GROUP_HANDSHAKE:
            case COMPLETED:   // joined, but no network yet: getting an address
                return true;
            default:
                return false;
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        GlassPopup p = new GlassPopup(this, root);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && !p.isEmpty() && !sawNonHdmi) p.addSeparator();
                if (!hdmi) sawNonHdmi = true;
                // This TV reports "standby" for every port whether or not something is plugged in, so only
                // definite states are shown.
                String status = state == TvInputManager.INPUT_STATE_CONNECTED ? getString(R.string.connected)
                        : state == TvInputManager.INPUT_STATE_DISCONNECTED ? getString(R.string.not_connected) : null;
                p.addRow(getDrawable(hdmi ? R.drawable.ic_input : R.drawable.ic_av), true, label, status,
                        state == TvInputManager.INPUT_STATE_CONNECTED, true,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (!p.isEmpty()) p.addSeparator();
            final Intent liveTvIntent = liveTv;
            p.addRow(R.drawable.ic_livetv, getString(R.string.live_tv), () -> open(liveTvIntent));
        }
        if (p.isEmpty()) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        p.showAt(anchor, Math.max(anchor.getWidth(), (int) (150 * density)));
    }
}
