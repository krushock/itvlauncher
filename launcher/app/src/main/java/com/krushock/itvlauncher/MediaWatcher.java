package com.krushock.itvlauncher;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's notification listener (switched on over adb at install time: cmd notification allow_listener).
 * Android only shows other apps' media sessions to a listener, which Now Playing needs, and it feeds the
 * notifications bell: with the stock launcher disabled, Google TV has no other place to show notifications.
 * Nothing it reads leaves the TV.
 */
public class MediaWatcher extends NotificationListenerService {

    /** Told on the main thread whenever the notifications to show change. */
    interface Listener {
        void onNotificationsChanged();
    }

    private static MediaWatcher instance;
    private static Listener listener;

    static void setListener(Listener l) {
        listener = l;
    }

    /** Whether the listener is switched on and connected (the bell hides otherwise). */
    static boolean connected() {
        return instance != null;
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        changed();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        changed();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        changed();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        changed();
    }

    private static void changed() {
        if (listener != null) listener.onNotificationsChanged();
    }

    /**
     * What the bell shows, newest first: notifications a person would want to read. Left out: ongoing ones (playback,
     * downloads, "connected"), foreground-service notices, group summaries, media controls, ones with no text, and
     * the launcher's own.
     */
    static List<StatusBarNotification> visible() {
        MediaWatcher w = instance;
        if (w == null) return Collections.emptyList();
        StatusBarNotification[] all;
        try {
            all = w.getActiveNotifications();
        } catch (Exception e) {
            return Collections.emptyList();
        }
        List<StatusBarNotification> out = new ArrayList<>();
        if (all == null) return out;
        for (StatusBarNotification sbn : all) {
            Notification n = sbn.getNotification();
            if (sbn.isOngoing() || !sbn.isClearable()) continue;
            if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) continue;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            if (Notification.CATEGORY_TRANSPORT.equals(n.category)) continue;
            if (sbn.getPackageName().equals(w.getPackageName())) continue;
            if (TextUtils.isEmpty(title(sbn)) && TextUtils.isEmpty(text(sbn))) continue;
            out.add(sbn);
        }
        out.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        return out;
    }

    static CharSequence title(StatusBarNotification sbn) {
        return sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
    }

    static CharSequence text(StatusBarNotification sbn) {
        android.os.Bundle x = sbn.getNotification().extras;
        CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        return big != null ? big : x.getCharSequence(Notification.EXTRA_TEXT);
    }

    /** Dismisses one notification, as swiping it away on a phone would. */
    static void dismiss(StatusBarNotification sbn) {
        MediaWatcher w = instance;
        if (w != null) w.cancelNotification(sbn.getKey());
    }
}
