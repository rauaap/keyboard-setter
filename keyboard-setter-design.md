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
  2. Debounce: ignore repeat events for the same package within a short window (e.g. 300ms) to avoid spamming settings writes when dialogs/popups fire extra events within the same app.
  3. Look up the package in the mapping store.
  4. If a mapping exists and differs from the currently active IME, write the new IME ID.
  5. If no mapping exists, do nothing (leave whatever IME is currently active — no "default" fallback that surprises the user).

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

## Edge cases to handle explicitly

| Case | Handling |
|---|---|
| `WRITE_SECURE_SETTINGS` revoked (OEM battery cleanup, reinstall) | Detect on service start and on each failed write (catch `SecurityException`); surface a persistent notification prompting re-grant, don't just silently stop working |
| Mapped IME uninstalled | Detect via `getInputMethodList()` not containing the stored component; drop the stale mapping and notify the user next time they open the app, rather than trying to switch to a nonexistent IME |
| Rapid app switching (e.g. multitasking/split-screen) | Debounce window handles most of this; consider ignoring events from known system/launcher packages entirely |
| Split-screen / multi-window with two different mapped apps visible at once | Out of scope for v1 — last event wins, document this as a known limitation rather than trying to solve it |
| Device reboot | `AccessibilityService` needs to be re-enabled by the user after reboot on some OEM skins regardless of manifest config — document this as a known Android limitation, not a bug in this app |
| OEM aggressive service killing (MIUI, One UI, etc.) | Out of scope to "fix" — document as a known constraint; user may need to whitelist the app from battery optimization |

## Manifest / permissions summary

- `BIND_ACCESSIBILITY_SERVICE` (system-granted on service binding, standard)
- `WRITE_SECURE_SETTINGS` (manual one-time adb grant, documented above)
- Accessibility service config XML: `typeWindowStateChanged` only, `canRetrieveWindowContent="false"`

## Open questions for implementation

- SharedPreferences vs. a tiny SQLite table — recommend SharedPreferences for v1 given the data shape (flat string->string map), revisit only if the mapping needs to grow richer (e.g. per-orientation, per-field).
- ~~Whether to ship a default/fallback IME when leaving a mapped app for an unmapped one, or always leave the last-active IME in place.~~ Resolved by testing: restore the IME that was active immediately before entering mapped territory, rather than leaving the mapped app's IME in place. "Leave it as-is" turned out to be actively bad once a mapped IME can be a disabled, single-app-only keyboard — there'd be no manual way back. Only restore if the user hasn't manually overridden the forced IME in the meantime.
