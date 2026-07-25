package com.rauaap.keyboardsetter;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.List;

/**
 * Watches foreground app changes and swaps the active IME to match the
 * user-defined mapping in {@link MappingStore}. No window content is read —
 * only the package name carried directly on the event.
 */
public class ForegroundWatcherService extends AccessibilityService {

    private static final long DEBOUNCE_MS = 300;
    private static final String NOTIFICATION_CHANNEL_ID = "permission_revoked";
    private static final int NOTIFICATION_ID = 1;

    private static final String TAG = "ForegroundWatcher";

    private String lastPackageName;
    private long lastEventTimeMs;

    // IME to restore once we leave mapped territory, and the IME we last forced —
    // used to tell "still what we set" apart from "user manually changed it since".
    private String restoreIme;
    private String appliedIme;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // The manifest-declared XML config isn't reliably picked up on all ROMs;
        // setting it explicitly here guarantees the event mask actually takes effect.
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        info.flags = 0;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence packageNameCs = event.getPackageName();
        if (packageNameCs == null) {
            return;
        }
        String packageName = packageNameCs.toString();
        // A software keyboard's own window briefly becomes the "foreground" package
        // when it shows or hides — that's not a real app switch and must not be
        // treated as leaving mapped territory, or every switch immediately undoes itself.
        if (isImePackage(packageName)) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (packageName.equals(lastPackageName) && (now - lastEventTimeMs) < DEBOUNCE_MS) {
            return;
        }
        lastPackageName = packageName;
        lastEventTimeMs = now;

        String mappedIme = MappingStore.get(this, packageName);
        String currentIme = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);

        if (mappedIme != null) {
            if (mappedIme.equals(currentIme)) {
                return;
            }
            // Only remember the pre-automation IME once, on the first hop into mapped
            // territory — a later mapped app must not clobber it with the previous
            // mapped app's IME, or we'd never find our way back to the real original.
            if (restoreIme == null) {
                restoreIme = currentIme;
            }
            if (applyIme(mappedIme)) {
                appliedIme = mappedIme;
            }
            return;
        }

        // Unmapped app: leave mapped territory. Only restore if the active IME is
        // still the one we forced — if the user manually changed it in the meantime,
        // respect that instead of clobbering their choice.
        if (restoreIme != null) {
            if (appliedIme != null && appliedIme.equals(currentIme) && !restoreIme.equals(currentIme)) {
                applyIme(restoreIme);
            }
            restoreIme = null;
            appliedIme = null;
        }
    }

    private boolean isImePackage(String packageName) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imes = imm.getInputMethodList();
        for (InputMethodInfo ime : imes) {
            if (ime.getPackageName().equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean applyIme(String imeId) {
        try {
            Settings.Secure.putString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, imeId);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Permission revoked while switching to " + imeId, e);
            notifyPermissionRevoked();
            return false;
        }
    }

    @Override
    public void onInterrupt() {
        // No-op: nothing to tear down between events.
    }

    private void notifyPermissionRevoked() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_permission_revoked),
                NotificationManager.IMPORTANCE_HIGH);
        manager.createNotificationChannel(channel);

        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(getString(R.string.notification_permission_revoked_title))
                .setContentText(getString(R.string.notification_permission_revoked_text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();

        manager.notify(NOTIFICATION_ID, notification);
    }
}
