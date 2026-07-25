package com.rauaap.keyboardsetter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private LinearLayout permissionBanner;
    private LinearLayout accessibilityBanner;
    private LinearLayout mappingListContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        permissionBanner = new LinearLayout(this);
        permissionBanner.setOrientation(LinearLayout.VERTICAL);
        root.addView(permissionBanner);

        accessibilityBanner = new LinearLayout(this);
        accessibilityBanner.setOrientation(LinearLayout.VERTICAL);
        root.addView(accessibilityBanner);

        TextView mappingsHeader = new TextView(this);
        mappingsHeader.setText(R.string.mappings_header);
        mappingsHeader.setTextSize(18);
        mappingsHeader.setPadding(0, dp(16), 0, dp(8));
        root.addView(mappingsHeader);

        mappingListContainer = new LinearLayout(this);
        mappingListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mappingListContainer);

        Button addButton = new Button(this);
        addButton.setText(R.string.add_mapping_button);
        addButton.setOnClickListener(v -> showAppPicker());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = dp(16);
        root.addView(addButton, addParams);

        TextView tip = new TextView(this);
        tip.setText(R.string.disabled_keyboard_tip);
        tip.setTextSize(13);
        tip.setAlpha(0.7f);
        tip.setPadding(0, dp(16), 0, 0);
        root.addView(tip);

        return scroll;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private void refresh() {
        refreshPermissionBanner();
        refreshAccessibilityBanner();
        refreshMappingList();
    }

    private void refreshPermissionBanner() {
        permissionBanner.removeAllViews();
        boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            return;
        }
        TextView message = new TextView(this);
        message.setText(R.string.permission_banner_message);
        permissionBanner.addView(message);

        TextView command = new TextView(this);
        command.setText(getString(R.string.permission_banner_command, getPackageName()));
        command.setTextIsSelectable(true);
        command.setTypeface(Typeface.MONOSPACE);
        command.setPadding(0, dp(8), 0, dp(16));
        permissionBanner.addView(command);
    }

    private void refreshAccessibilityBanner() {
        accessibilityBanner.removeAllViews();
        if (isAccessibilityServiceEnabled()) {
            return;
        }
        TextView message = new TextView(this);
        message.setText(R.string.accessibility_banner_message);
        accessibilityBanner.addView(message);

        Button openSettings = new Button(this);
        openSettings.setText(R.string.accessibility_banner_button);
        openSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        params.bottomMargin = dp(16);
        accessibilityBanner.addView(openSettings, params);
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName self = new ComponentName(this, ForegroundWatcherService.class);
        for (String piece : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(piece);
            if (component != null && component.equals(self)) {
                return true;
            }
        }
        return false;
    }

    private void refreshMappingList() {
        mappingListContainer.removeAllViews();
        Map<String, String> mappings = MappingStore.getAll(this);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imes = imm.getInputMethodList();

        boolean removedStale = false;
        List<String> packages = new ArrayList<>(mappings.keySet());
        Collections.sort(packages);
        for (String pkg : packages) {
            String imeId = mappings.get(pkg);
            InputMethodInfo ime = findIme(imes, imeId);
            if (ime == null) {
                MappingStore.remove(this, pkg);
                removedStale = true;
                continue;
            }
            addMappingRow(pkg, ime);
        }

        if (mappingListContainer.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.mappings_empty);
            mappingListContainer.addView(empty);
        }

        if (removedStale) {
            Toast.makeText(this, R.string.stale_mapping_removed, Toast.LENGTH_LONG).show();
        }
    }

    private InputMethodInfo findIme(List<InputMethodInfo> imes, String componentId) {
        for (InputMethodInfo ime : imes) {
            if (ime.getId().equals(componentId)) {
                return ime;
            }
        }
        return null;
    }

    private void addMappingRow(String packageName, InputMethodInfo ime) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView label = new TextView(this);
        label.setText(getString(R.string.mapping_row_label, appLabel(packageName), ime.loadLabel(getPackageManager())));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, labelParams);

        Button remove = new Button(this);
        remove.setText(R.string.remove_mapping_button);
        remove.setOnClickListener(v -> {
            MappingStore.remove(this, packageName);
            refreshMappingList();
        });
        row.addView(remove);

        mappingListContainer.addView(row);
    }

    private String appLabel(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return getPackageManager().getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private void showAppPicker() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcherIntent, 0);
        Collections.sort(resolved, (a, b) -> a.loadLabel(getPackageManager()).toString()
                .compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));

        List<String> packageNames = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            String pkg = info.activityInfo.packageName;
            if (packageNames.contains(pkg)) {
                continue;
            }
            packageNames.add(pkg);
            labels.add(info.loadLabel(getPackageManager()).toString());
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.app_picker_title)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> showImePicker(packageNames.get(which)))
                .show();
    }

    private void showImePicker(String packageName) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        // Disabled IMEs work fine as an automated switch target and are actually the
        // recommended choice for a single-app keyboard: disabled means it can't be
        // picked manually (no switcher entry, no cycle-through), only by this app.
        List<InputMethodInfo> imes = imm.getInputMethodList();
        if (imes.isEmpty()) {
            Toast.makeText(this, R.string.no_imes_installed, Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] labels = new CharSequence[imes.size()];
        for (int i = 0; i < imes.size(); i++) {
            labels[i] = imes.get(i).loadLabel(getPackageManager());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ime_picker_title)
                .setItems(labels, (dialog, which) -> {
                    MappingStore.set(this, packageName, imes.get(which).getId());
                    refreshMappingList();
                })
                .show();
    }
}
