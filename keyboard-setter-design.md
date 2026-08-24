# Per-App IME Switcher — Design Doc

## Problem

Android tracks a single global default input method (`Settings.Secure.DEFAULT_INPUT_METHOD`). There is no OS-level concept of "app X always uses keyboard Y" — switching keyboards is a manual, global action. This app closes that gap by watching foreground app changes and silently swapping the active IME based on a user-defined mapping.

## Goals

- Detect foreground app changes with low latency and no polling.
- Maintain a persistent `package name -> IME component ID` map, editable by the user.
- Switch the active IME automatically on app change, with no user interaction required per switch.
- Survive reboots, IME uninstalls, and permission revocation gracefully (no crash loops, no silent total failure).

## Non-goals

- No support for per-*field* switching within a single app (e.g. different IME for a search bar vs. a chat box). Package-level granularity only.
- No root requirement. Everything works via a single one-time ADB-granted permission.
- No cloud sync / backup of the mapping — local storage only (v1).

## Architecture

Three components:

### 1. `AccessibilityService` — event listener

- Declared with `android:accessibilityEventTypes="typeWindowStateChanged"`.
- Does **not** request `canRetrieveWindowContent` — package name is available directly on the event, no window inspection needed. Keeps the permission footprint minimal and avoids the scarier "read screen content" consent prompt.
- On each `TYPE_WINDOW_STATE_CHANGED` event:
  1. Read `event.getPackageName()`.
  2. Confirm the event describes a real app switch, not merely a window becoming
     active. `TYPE_WINDOW_STATE_CHANGED` fires for overlays, popups, toasts, the
     notification shade, the keyguard and IME show/hide too, each carrying its own
     package. Resolve `event.getPackageName()` + `event.getClassName()` through
     `PackageManager.getActivityInfo()`: real activities resolve, everything else
     throws `NameNotFoundException` and is ignored. See "Window events are not app
     switches" below for why this is load-bearing.
  3. Debounce: ignore repeat events for the same package within a short window (e.g. 300ms) to avoid spamming settings writes when dialogs/popups fire extra events within the same app.
  4. Look up the package in the mapping store.
  5. If a mapping exists and differs from the currently active IME, write the new IME ID.
  6. If no mapping exists, do nothing (leave whatever IME is currently active — no "default" fallback that surprises the user).

### 2. IME switch mechanism

Use `Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, imeId)` rather than `InputMethodManager.setInputMethod()`, because the latter requires an `IBinder` window token that a background service doesn't hold. The Settings.Secure write works from a service context with no token.

Requires `android.permission.WRITE_SECURE_SETTINGS`, which cannot be requested at runtime through a normal dialog — it must be granted once via:

```
adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS
```

The app must detect at launch whether this permission is currently held (`checkSelfPermission`) and, if not, show clear instructions (including the exact adb command with the package name filled in) rather than failing silently later.

### 3. Mapping store + settings UI

- Simple local persistence (SharedPreferences or a small SQLite table — SharedPreferences is enough for a package->string map, no need to reach for Room).
- UI: list of installed apps (via `PackageManager.getInstalledApplications`), each with a dropdown of currently installed IMEs (via `InputMethodManager.getInputMethodList()`), storing the selected `ComponentName.flattenToString()`.
- Store IME choices as component strings (`package/.ClassName`), not display names — display names change with locale, component IDs don't.

## Data flow

```
App switch on screen
   -> AccessibilityService.onAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)
   -> extract package name
   -> activity-window check (drop overlays, popups, shade, keyguard, IME windows)
   -> debounce check
   -> mapping lookup (SharedPreferences read)
   -> if mapped app:
        remember pre-automation IME (once, on first hop into mapped territory)
        if mapped IME != current IME: Settings.Secure.putString(DEFAULT_INPUT_METHOD, mappedIme)
   -> if unmapped app and a pre-automation IME is remembered:
        if current IME is still the one we forced: restore the remembered IME
        else (user manually changed it since): respect that, forget the remembered IME
```

Restoring on exit (rather than "leave the last-active IME in place", see Open
questions below) matters more than it might look: `Settings.Secure.DEFAULT_INPUT_METHOD`
writes take effect even when the target IME is **disabled** in system
settings (confirmed by direct testing — not documented Android behavior, and
contrary to what the picker UI originally assumed). A disabled IME has no
manual switcher entry and doesn't participate in the swipe-to-cycle gesture,
so it's unreachable by hand — which is actually the *recommended* setup for
a single-app keyboard (see the in-app tip). But it also means there is no
manual fallback if it's left active after leaving the app, so restore-on-exit
is load-bearing, not just tidiness.

## Window events are not app switches

The mapping is defined over foreground *apps*, but the only signal available is
*window* events — and those two diverge exactly when another package draws over an
app that hasn't gone anywhere.

Confirmed on device: copying text in a mapped app swapped the keyboard back to the
pre-automation default. Android 13+ renders a clipboard confirmation overlay from
`com.android.systemui`, which fires `TYPE_WINDOW_STATE_CHANGED` carrying that
package. Unmapped, so the service concluded the user had left mapped territory and
restored. The notification shade, volume panel, toasts and the keyguard all do the
same thing.

What makes this more than cosmetic is that these windows never background the app.
Because the mapped app's window never *changes state*, dismissing the overlay
produces no return event, so nothing re-applies the mapping — the wrong keyboard
persists until some later event happens to carry the mapped package again. Note the
contrast with genuine interruptions that *are* activities (permission dialogs, share
sheets): those do fire a return event on dismissal and recover on their own, so they
need no special handling.

The filter is the activity check in step 2 above. Two properties worth keeping:

- **It fails safe.** An unresolvable window is treated as "not an app switch", which
  leaves the current IME alone. The manifest's `queries` filter also hides
  non-launchable packages, so those fail to resolve too — same direction, and benign
  for everything except the home screen (see below).
- **It subsumes the IME-package check** it replaced. A soft input window reports
  `android.inputmethodservice.SoftInputWindow`, not an activity, so keyboard
  show/hide is filtered without a per-event `getInputMethodList()` call — and a
  keyboard's own *settings* activity is now correctly seen as a real app switch.

### The home screen is the exception

Going home is the most common way to leave a mapped app, and it's also the window
least likely to resolve — so the filter silently broke restore-on-exit for the case
that matters most (confirmed on device: leaving Termux via gesture *or* the 3-button
home key kept Unexpected Keyboard active). Two independent reasons it fails:

- **Package visibility.** A launcher isn't in its own app drawer, so it has no
  `CATEGORY_LAUNCHER` activity — its home entry is `MAIN` + `CATEGORY_HOME` +
  `DEFAULT`. The `queries` filter therefore never made the launcher package visible,
  and `getActivityInfo()` throws `NameNotFoundException` no matter how correct the
  component is. Fixed by declaring a second `queries` intent for `MAIN` + `HOME`.
- **Class-name mismatch.** The event carries the *runtime* class
  (`Activity.getClass().getName()`), while `getActivityInfo()` wants the declared
  `android:name`. Launchers commonly register the HOME entry as an
  `<activity-alias>`, so the two differ even once the package is visible.

Hence the fallback: any window whose package is the current default home package
(`resolveActivity` on a `MAIN`/`HOME` intent) counts as a real app switch, class name
notwithstanding. Home-package popups (widget picker, icon long-press menu) get let
through by this too, which is harmless — the home screen is already foreground, so an
unmapped home re-runs a restore that has already cleared its state, and a mapped home
re-applies an IME that's already active.

Rejected alternatives: denylisting `com.android.systemui` and friends fixes one
symptom at a time and breaks on the next OEM skin. `getWindows()` /
`AccessibilityWindowInfo.getType()` is the most correct signal, but
`FLAG_RETRIEVE_INTERACTIVE_WINDOWS` requires `canRetrieveWindowContent="true"` and
the "read screen content" consent prompt this design deliberately avoids.

## Edge cases to handle explicitly

| Case | Handling |
|---|---|
| `WRITE_SECURE_SETTINGS` revoked (OEM battery cleanup, reinstall) | Detect on service start and on each failed write (catch `SecurityException`); surface a persistent notification prompting re-grant, don't just silently stop working |
| Mapped IME uninstalled | Detect via `getInputMethodList()` not containing the stored component; drop the stale mapping and notify the user next time they open the app, rather than trying to switch to a nonexistent IME |
| Overlay windows from other packages (clipboard confirmation, notification shade, volume panel, keyguard, toasts) | Ignore any event whose window doesn't resolve to a declared activity — see "Window events are not app switches" above |
| Leaving a mapped app via home (gesture or home button) | The launcher's window doesn't resolve as an activity, so the activity check alone drops it and never restores. Declare `MAIN`/`HOME` in `queries` and accept any window from the current home package — see "The home screen is the exception" above |
| Rapid app switching (e.g. multitasking/split-screen) | Debounce window handles most of this, and the activity check drops the transient non-app windows that used to slip through |
| Split-screen / multi-window with two different mapped apps visible at once | Out of scope for v1 — last event wins, document this as a known limitation rather than trying to solve it |
| Device reboot | `AccessibilityService` needs to be re-enabled by the user after reboot on some OEM skins regardless of manifest config — document this as a known Android limitation, not a bug in this app |
| OEM aggressive service killing (MIUI, One UI, etc.) | Out of scope to "fix" — document as a known constraint; user may need to whitelist the app from battery optimization |

## Manifest / permissions summary

- `BIND_ACCESSIBILITY_SERVICE` (system-granted on service binding, standard)
- `WRITE_SECURE_SETTINGS` (manual one-time adb grant, documented above)
- Accessibility service config XML: `typeWindowStateChanged` only, `canRetrieveWindowContent="false"` (kept false deliberately — see the rejected alternatives above)

## Open questions for implementation

- SharedPreferences vs. a tiny SQLite table — recommend SharedPreferences for v1 given the data shape (flat string->string map), revisit only if the mapping needs to grow richer (e.g. per-orientation, per-field).
- ~~Whether to ship a default/fallback IME when leaving a mapped app for an unmapped one, or always leave the last-active IME in place.~~ Resolved by testing: restore the IME that was active immediately before entering mapped territory, rather than leaving the mapped app's IME in place. "Leave it as-is" turned out to be actively bad once a mapped IME can be a disabled, single-app-only keyboard — there'd be no manual way back. Only restore if the user hasn't manually overridden the forced IME in the meantime.
