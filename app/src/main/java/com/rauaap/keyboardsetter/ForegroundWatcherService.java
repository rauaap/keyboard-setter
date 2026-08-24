package com.rauaap.keyboardsetter;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

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
        // TYPE_WINDOW_STATE_CHANGED fires for any window becoming active, not just app
        // switches — the clipboard overlay, notification shade, toasts, selection popups
        // and keyboard show/hide all fire it too, carrying their own package. Treating
        // those as "the user left this app" restores the pre-automation IME while the
        // mapped app is still very much in the foreground, and for overlays that never
        // background the app there's no return event to undo it. Only windows that
        // resolve to a declared activity represent a real foreground app change.
        if (!isActivityWindow(packageName, event.getClassName())) {
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

    /**
     * True if the window that fired the event is a declared activity of the given
     * package. Overlays, popups, toasts and IME windows report a plain view or window
     * class here rather than an activity, so they resolve to nothing and are ignored.
     * Failing to resolve also covers packages hidden by the manifest's {@code queries}
     * filter — erring toward "not an app switch", which leaves the current IME alone.
     *
     * <p>The home screen is the exception that has to be rescued: it's the most common
     * way to leave a mapped app, and its window is the one most likely not to resolve
     * (launchers commonly register the HOME entry as an {@code <activity-alias>}, so the
     * runtime class the event carries isn't a declared activity name). Treat any window
     * belonging to the current home package as a real app switch.
     */
    private boolean isActivityWindow(String packageName, CharSequence className) {
        if (className != null) {
            try {
                getPackageManager().getActivityInfo(
                        new ComponentName(packageName, className.toString()), 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                // Fall through to the home check.
            }
        }
        return packageName.equals(homePackage());
    }

    /**
     * Package of the current default home app, or null if it can't be resolved.
     * Resolved per call rather than cached so switching launchers takes effect without
     * restarting the service; this only runs on the path where the activity lookup
     * already failed, and costs the same single PackageManager call it replaces.
     */
    private String homePackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info == null || info.activityInfo == null ? null : info.activityInfo.packageName;
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
