/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.util;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT;

import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import com.android.launcher3.PendingAddItemInfo;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.LauncherAppWidgetInfo;
import com.android.launcher3.model.data.PromiseAppInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;

import java.net.URISyntaxException;
import java.util.List;

/**
 * Utility methods using package manager
 */
public class PackageManagerHelper {

    private static final String TAG = "PackageManagerHelper";

    private final Context mContext;
    private final PackageManager mPm;
    private final LauncherApps mLauncherApps;

    public PackageManagerHelper(Context context) {
        mContext = context;
        mPm = context.getPackageManager();
        mLauncherApps = context.getSystemService(LauncherApps.class);
    }

    /**
     * Returns true if the app can possibly be on the SDCard. This is just a workaround and doesn't
     * guarantee that the app is on SD card.
     */
    public boolean isAppOnSdcard(String packageName, UserHandle user) {
        ApplicationInfo info = getApplicationInfo(
                packageName, user, PackageManager.MATCH_UNINSTALLED_PACKAGES);
        return info != null && (info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    public static boolean isAppInstalled(PackageManager pm, String packageName, int flags) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, flags);
            return info != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns whether the target app is suspended for a given user as per
     * {@link android.app.admin.DevicePolicyManager#isPackageSuspended}.
     */
    public boolean isAppSuspended(String packageName, UserHandle user) {
        ApplicationInfo info = getApplicationInfo(packageName, user, 0);
        return info != null && isAppSuspended(info);
    }

    /**
     * Returns the application info for the provided package or null
     */
    public ApplicationInfo getApplicationInfo(String packageName, UserHandle user, int flags) {
        if (Utilities.ATLEAST_OREO) {
            try {
                ApplicationInfo info = mLauncherApps.getApplicationInfo(packageName, flags, user);
                return (info.flags & ApplicationInfo.FLAG_INSTALLED) == 0 || !info.enabled
                        ? null : info;
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        } else {
            final boolean isPrimaryUser = Process.myUserHandle().equals(user);
            if (!isPrimaryUser && (flags == 0)) {
                // We are looking for an installed app on a secondary profile. Prior to O, the only
                // entry point for work profiles is through the LauncherActivity.
                List<LauncherActivityInfo> activityList =
                        mLauncherApps.getActivityList(packageName, user);
                return activityList.size() > 0 ? activityList.get(0).getApplicationInfo() : null;
            }
            try {
                ApplicationInfo info = mPm.getApplicationInfo(packageName, flags);
                // There is no way to check if the app is installed for managed profile. But for
                // primary profile, we can still have this check.
                if (isPrimaryUser && ((info.flags & ApplicationInfo.FLAG_INSTALLED) == 0)
                        || !info.enabled) {
                    return null;
                }
                return info;
            } catch (PackageManager.NameNotFoundException e) {
                // Package not found
                return null;
            }
        }
    }

    public boolean isSafeMode() {
        return mContext.getPackageManager().isSafeMode();
    }

    public Intent getAppLaunchIntent(String pkg, UserHandle user) {
        List<LauncherActivityInfo> activities = mLauncherApps.getActivityList(pkg, user);
        return activities.isEmpty() ? null :
                AppInfo.makeLaunchIntent(activities.get(0));
    }

    /**
     * Returns whether an application is suspended as per
     * {@link android.app.admin.DevicePolicyManager#isPackageSuspended}.
     */
    public static boolean isAppSuspended(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
    }

    /**
     * Returns true if {@param srcPackage} has the permission required to start the activity from
     * {@param intent}. If {@param srcPackage} is null, then the activity should not need
     * any permissions
     */
    public boolean hasPermissionForActivity(Intent intent, String srcPackage) {
        ResolveInfo target = mPm.resolveActivity(intent, 0);
        if (target == null) {
            // Not a valid target
            return false;
        }
        if (TextUtils.isEmpty(target.activityInfo.permission)) {
            // No permission is needed
            return true;
        }
        if (TextUtils.isEmpty(srcPackage)) {
            // The activity requires some permission but there is no source.
            return false;
        }

        // Source does not have sufficient permissions.
        if(mPm.checkPermission(target.activityInfo.permission, srcPackage) !=
                PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        // On M and above also check AppOpsManager for compatibility mode permissions.
        if (TextUtils.isEmpty(AppOpsManager.permissionToOp(target.activityInfo.permission))) {
            // There is no app-op for this permission, which could have been disabled.
            return true;
        }

        // There is no direct way to check if the app-op is allowed for a particular app. Since
        // app-op is only enabled for apps running in compatibility mode, simply block such apps.

        try {
            return mPm.getApplicationInfo(srcPackage, 0).targetSdkVersion >= Build.VERSION_CODES.M;
        } catch (NameNotFoundException e) { }

        return false;
    }

    public Intent getMarketIntent(String packageName) {
        return new Intent(Intent.ACTION_VIEW)
                .setData(new Uri.Builder()
                        .scheme("market")
                        .authority("details")
                        .appendQueryParameter("id", packageName)
                        .build())
                .putExtra(Intent.EXTRA_REFERRER, new Uri.Builder().scheme("android-app")
                        .authority(mContext.getPackageName()).build());
    }

    public Intent getUninstallIntent(String packageName) {
        return new Intent(Intent.ACTION_UNINSTALL_PACKAGE)
                .setData(Uri.parse("package:" + packageName))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
    }

    /**
     * Creates a new market search intent.
     */
    public static Intent getMarketSearchIntent(Context context, String query) {
        try {
            Intent intent = Intent.parseUri(context.getString(R.string.market_search_intent), 0);
            if (!TextUtils.isEmpty(query)) {
                intent.setData(
                        intent.getData().buildUpon().appendQueryParameter("q", query).build());
            }
            return intent;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static Intent getStyleWallpapersIntent(Context context) {
        return new Intent(Intent.ACTION_SET_WALLPAPER).setComponent(
                new ComponentName(context.getString(R.string.wallpaper_picker_package),
                "com.android.customization.picker.CustomizationPickerActivity"));
    }

    /**
     * Starts the details activity for {@code info}
     */
    public void startDetailsActivityForInfo(ItemInfo info, Rect sourceBounds, Bundle opts) {
        if (info instanceof PromiseAppInfo) {
            PromiseAppInfo promiseAppInfo = (PromiseAppInfo) info;
            mContext.startActivity(promiseAppInfo.getMarketIntent(mContext));
            return;
        }
        ComponentName componentName = null;
        if (info instanceof AppInfo) {
            componentName = ((AppInfo) info).componentName;
        } else if (info instanceof WorkspaceItemInfo) {
            componentName = info.getTargetComponent();
        } else if (info instanceof PendingAddItemInfo) {
            componentName = ((PendingAddItemInfo) info).componentName;
        } else if (info instanceof LauncherAppWidgetInfo) {
            componentName = ((LauncherAppWidgetInfo) info).providerName;
        }
        if (componentName != null) {
            try {
                mLauncherApps.startAppDetailsActivity(componentName, info.user, sourceBounds, opts);
            } catch (SecurityException | ActivityNotFoundException e) {
                Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Unable to launch settings", e);
            }
        }
    }

    /**
     * Creates an intent filter to listen for actions with a specific package in the data field.
     */
    public static IntentFilter getPackageFilter(String pkg, String... actions) {
        IntentFilter packageFilter = new IntentFilter();
        for (String action : actions) {
            packageFilter.addAction(action);
        }
        packageFilter.addDataScheme("package");
        packageFilter.addDataSchemeSpecificPart(pkg, PatternMatcher.PATTERN_LITERAL);
        return packageFilter;
    }

    public static boolean isSystemApp(Context context, String pkgName) {
        return isSystemApp(context, null, pkgName);
    }

    public static boolean isSystemApp(Context context, Intent intent) {
        return isSystemApp(context, intent, null);
    }

    public static boolean isSystemApp(Context context, Intent intent, String pkgName) {
        PackageManager pm = context.getPackageManager();
        String packageName = null;
        // If the intent is not null, let's get the package name from the intent.
        if (intent != null) {
            ComponentName cn = intent.getComponent();
            if (cn == null) {
                ResolveInfo info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if ((info != null) && (info.activityInfo != null)) {
                    packageName = info.activityInfo.packageName;
                }
            } else {
                packageName = cn.getPackageName();
            }
        }
        // Otherwise we have the package name passed from the method.
        else {
            packageName = pkgName;
        }
        // Check if the provided package is a system app.
        if (packageName != null) {
            try {
                PackageInfo info = pm.getPackageInfo(packageName, 0);
                return (info != null) && (info.applicationInfo != null) &&
                        ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            } catch (NameNotFoundException e) {
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * Finds a system apk which had a broadcast receiver listening to a particular action.
     * @param action intent action used to find the apk
     * @return a pair of apk package name and the resources.
     */
    public static Pair<String, Resources> findSystemApk(String action, PackageManager pm) {
        final Intent intent = new Intent(action);
        for (ResolveInfo info : pm.queryBroadcastReceivers(intent, MATCH_SYSTEM_ONLY)) {
            final String packageName = info.activityInfo.packageName;
            try {
                final Resources res = pm.getResourcesForApplication(packageName);
                return Pair.create(packageName, res);
            } catch (NameNotFoundException e) {
                Log.w(TAG, "Failed to find resources for " + packageName);
            }
        }
        return null;
    }

    /**
     * Returns true if the intent is a valid launch intent for a launcher activity of an app.
     * This is used to identify shortcuts which are different from the ones exposed by the
     * applications' manifest file.
     *
     * @param launchIntent The intent that will be launched when the shortcut is clicked.
     */
    public static boolean isLauncherAppTarget(Intent launchIntent) {
        if (launchIntent != null
                && Intent.ACTION_MAIN.equals(launchIntent.getAction())
                && launchIntent.getComponent() != null
                && launchIntent.getCategories() != null
                && launchIntent.getCategories().size() == 1
                && launchIntent.hasCategory(Intent.CATEGORY_LAUNCHER)
                && TextUtils.isEmpty(launchIntent.getDataString())) {
            // An app target can either have no extra or have ItemInfo.EXTRA_PROFILE.
            Bundle extras = launchIntent.getExtras();
            return extras == null || extras.keySet().isEmpty();
        }
        return false;
    }

    /**
     * Returns true if Launcher has the permission to access shortcuts.
     * @see LauncherApps#hasShortcutHostPermission()
     */
    public static boolean hasShortcutsPermission(Context context) {
        try {
            return context.getSystemService(LauncherApps.class).hasShortcutHostPermission();
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Failed to make shortcut manager call", e);
        }
        return false;
    }

    public static boolean isShortcut(ItemInfo item) {
        if (item.itemType == ITEM_TYPE_DEEP_SHORTCUT ||
            item.itemType == ITEM_TYPE_SHORTCUT) {
            return true;
        }
        return false;
    }
}
