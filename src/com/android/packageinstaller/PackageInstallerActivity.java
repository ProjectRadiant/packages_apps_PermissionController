/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package com.android.packageinstaller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.VerificationParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AppSecurityPermissions;
import android.widget.Button;
import android.widget.TabHost;
import android.widget.TextView;

import java.io.File;

/*
 * This activity is launched when a new application is installed via side loading
 * The package is first parsed and the user is notified of parse errors via a dialog.
 * If the package is successfully parsed, the user is notified to turn on the install unknown
 * applications setting. A memory check is made at this point and the user is notified of out
 * of memory conditions if any. If the package is already existing on the device,
 * a confirmation dialog (to replace the existing package) is presented to the user.
 * Based on the user response the package is then installed by launching InstallAppConfirm
 * sub activity. All state transitions are handled in this activity
 */
public class PackageInstallerActivity extends Activity implements OnCancelListener, OnClickListener {
    private static final String TAG = "PackageInstaller";

    private static final int REQUEST_TRUST_EXTERNAL_SOURCE = 1;

    private static final String SCHEME_FILE = "file";
    private static final String SCHEME_PACKAGE = "package";

    static final String EXTRA_CALLING_PACKAGE = "EXTRA_CALLING_PACKAGE";
    static final String EXTRA_ORIGINAL_SOURCE_INFO = "EXTRA_ORIGINAL_SOURCE_INFO";

    private int mSessionId = -1;
    private Uri mPackageURI;
    private Uri mOriginatingURI;
    private Uri mReferrerURI;
    private int mOriginatingUid = VerificationParams.NO_UID;
    private String mOriginatingPackage; // The package name corresponding to #mOriginatingUid

    private boolean localLOGV = false;
    PackageManager mPm;
    IPackageManager mIpm;
    AppOpsManager mAppOpsManager;
    UserManager mUserManager;
    PackageInstaller mInstaller;
    PackageInfo mPkgInfo;
    String mCallingPackage;
    ApplicationInfo mSourceInfo;

    // ApplicationInfo object primarily used for already existing applications
    private ApplicationInfo mAppInfo = null;

    // Buttons to indicate user acceptance
    private Button mOk;
    private Button mCancel;
    CaffeinatedScrollView mScrollView = null;
    private boolean mOkCanInstall = false;

    private PackageUtil.AppSnippet mAppSnippet;

    static final String PREFS_ALLOWED_SOURCES = "allowed_sources";

    private static final String TAB_ID_ALL = "all";
    private static final String TAB_ID_NEW = "new";

    // Dialog identifiers used in showDialog
    private static final int DLG_BASE = 0;
    private static final int DLG_PACKAGE_ERROR = DLG_BASE + 2;
    private static final int DLG_OUT_OF_SPACE = DLG_BASE + 3;
    private static final int DLG_INSTALL_ERROR = DLG_BASE + 4;
    private static final int DLG_UNKNOWN_SOURCES_RESTRICTED_FOR_USER = DLG_BASE + 5;
    private static final int DLG_NOT_SUPPORTED_ON_WEAR = DLG_BASE + 7;
    private static final int DLG_EXTERNAL_SOURCE_BLOCKED = DLG_BASE + 8;

    private void startInstallConfirm() {
        // We might need to show permissions, load layout with permissions
        if (mAppInfo != null) {
            bindUi(R.layout.install_confirm_perm_update, true);
        } else {
            bindUi(R.layout.install_confirm_perm, true);
        }

        ((TextView) findViewById(R.id.install_confirm_question))
                .setText(R.string.install_confirm_question);
        TabHost tabHost = (TabHost)findViewById(android.R.id.tabhost);
        tabHost.setup();
        ViewPager viewPager = (ViewPager)findViewById(R.id.pager);
        TabsAdapter adapter = new TabsAdapter(this, tabHost, viewPager);
        // If the app supports runtime permissions the new permissions will
        // be requested at runtime, hence we do not show them at install.
        boolean supportsRuntimePermissions = mPkgInfo.applicationInfo.targetSdkVersion
                >= Build.VERSION_CODES.M;
        boolean permVisible = false;
        mScrollView = null;
        mOkCanInstall = false;
        int msg = 0;

        AppSecurityPermissions perms = new AppSecurityPermissions(this, mPkgInfo);
        final int N = perms.getPermissionCount(AppSecurityPermissions.WHICH_ALL);
        if (mAppInfo != null) {
            msg = (mAppInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    ? R.string.install_confirm_question_update_system
                    : R.string.install_confirm_question_update;
            mScrollView = new CaffeinatedScrollView(this);
            mScrollView.setFillViewport(true);
            boolean newPermissionsFound = false;
            if (!supportsRuntimePermissions) {
                newPermissionsFound =
                        (perms.getPermissionCount(AppSecurityPermissions.WHICH_NEW) > 0);
                if (newPermissionsFound) {
                    permVisible = true;
                    mScrollView.addView(perms.getPermissionsView(
                            AppSecurityPermissions.WHICH_NEW));
                }
            }
            if (!supportsRuntimePermissions && !newPermissionsFound) {
                LayoutInflater inflater = (LayoutInflater)getSystemService(
                        Context.LAYOUT_INFLATER_SERVICE);
                TextView label = (TextView)inflater.inflate(R.layout.label, null);
                label.setText(R.string.no_new_perms);
                mScrollView.addView(label);
            }
            adapter.addTab(tabHost.newTabSpec(TAB_ID_NEW).setIndicator(
                    getText(R.string.newPerms)), mScrollView);
        }
        if (!supportsRuntimePermissions && N > 0) {
            permVisible = true;
            LayoutInflater inflater = (LayoutInflater)getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            View root = inflater.inflate(R.layout.permissions_list, null);
            if (mScrollView == null) {
                mScrollView = (CaffeinatedScrollView)root.findViewById(R.id.scrollview);
            }
            ((ViewGroup)root.findViewById(R.id.permission_list)).addView(
                        perms.getPermissionsView(AppSecurityPermissions.WHICH_ALL));
            adapter.addTab(tabHost.newTabSpec(TAB_ID_ALL).setIndicator(
                    getText(R.string.allPerms)), root);
        }
        if (!permVisible) {
            if (mAppInfo != null) {
                // This is an update to an application, but there are no
                // permissions at all.
                msg = (mAppInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        ? R.string.install_confirm_question_update_system_no_perms
                        : R.string.install_confirm_question_update_no_perms;
            } else {
                // This is a new application with no permissions.
                msg = R.string.install_confirm_question_no_perms;
            }

            // We do not need to show any permissions, load layout without permissions
            bindUi(R.layout.install_confirm, true);
            mScrollView = null;
        }
        if (msg != 0) {
            ((TextView)findViewById(R.id.install_confirm_question)).setText(msg);
        }
        if (mScrollView == null) {
            // There is nothing to scroll view, so the ok button is immediately
            // set to install.
            mOk.setText(R.string.install);
            mOkCanInstall = true;
        } else {
            mScrollView.setFullScrollAction(new Runnable() {
                @Override
                public void run() {
                    mOk.setText(R.string.install);
                    mOkCanInstall = true;
                }
            });
        }
    }

    private void showDialogInner(int id) {
        // TODO better fix for this? Remove dialog so that it gets created again
        removeDialog(id);
        showDialog(id);
    }

    @Override
    public Dialog onCreateDialog(int id, Bundle bundle) {
        ApplicationInfo sourceInfo = null;
        switch (id) {
        case DLG_PACKAGE_ERROR :
            return new AlertDialog.Builder(this)
                    .setMessage(R.string.Parse_error_dlg_text)
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setOnCancelListener(this)
                    .create();
        case DLG_OUT_OF_SPACE:
            // Guaranteed not to be null. will default to package name if not set by app
            CharSequence appTitle = mPm.getApplicationLabel(mPkgInfo.applicationInfo);
            String dlgText = getString(R.string.out_of_space_dlg_text,
                    appTitle.toString());
            return new AlertDialog.Builder(this)
                    .setMessage(dlgText)
                    .setPositiveButton(R.string.manage_applications, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            //launch manage applications
                            Intent intent = new Intent("android.intent.action.MANAGE_PACKAGE_STORAGE");
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            finish();
                        }
                    })
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Log.i(TAG, "Canceling installation");
                            finish();
                        }
                })
                  .setOnCancelListener(this)
                  .create();
        case DLG_INSTALL_ERROR :
            // Guaranteed not to be null. will default to package name if not set by app
            CharSequence appTitle1 = mPm.getApplicationLabel(mPkgInfo.applicationInfo);
            String dlgText1 = getString(R.string.install_failed_msg,
                    appTitle1.toString());
            return new AlertDialog.Builder(this)
                    .setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setMessage(dlgText1)
                    .setOnCancelListener(this)
                    .create();
        case DLG_NOT_SUPPORTED_ON_WEAR:
            return new AlertDialog.Builder(this)
                    .setMessage(R.string.wear_not_allowed_dlg_text)
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            setResult(RESULT_OK);
                            finish();
                        }
                    })
                    .setOnCancelListener(this)
                    .create();
        case DLG_UNKNOWN_SOURCES_RESTRICTED_FOR_USER:
            return new AlertDialog.Builder(this)
                    .setMessage(R.string.unknown_apps_user_restriction_dlg_text)
                    .setPositiveButton(android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    finish();
                                }
                            })
                    .setOnCancelListener(this)
                    .create();
        case DLG_EXTERNAL_SOURCE_BLOCKED:
            try {
                sourceInfo = mPm.getApplicationInfo(mOriginatingPackage, 0);
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Did not find app info for " + mOriginatingPackage);
                finish();
                break;
            }
            return new AlertDialog.Builder(this)
                    .setTitle(mPm.getApplicationLabel(sourceInfo))
                    .setIcon(mPm.getApplicationIcon(sourceInfo))
                    .setMessage(R.string.untrusted_external_source_warning)
                    .setPositiveButton(R.string.external_sources_settings,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Intent settingsIntent = new Intent();
                                    settingsIntent.setAction(
                                            Settings.ACTION_MANAGE_EXTERNAL_SOURCES);
                                    try {
                                        startActivityForResult(settingsIntent,
                                                REQUEST_TRUST_EXTERNAL_SOURCE);
                                    } catch (ActivityNotFoundException exc) {
                                        Log.e(TAG, "Settings activity not found for action: "
                                                + Settings.ACTION_MANAGE_EXTERNAL_SOURCES);
                                    }
                                }
                            })
                    .setOnCancelListener(dialog -> finish())
                    .setNegativeButton(R.string.cancel, (dialog, which) -> finish())
                    .create();
        }
        return null;
    }

    @Override
    public void onActivityResult(int request, int result, Intent data) {
        // currently just a hook for partners to implement "allow once" feature
        // TODO: Use this to resume install request when user has explicitly trusted the source
        // by changing the settings
        if (request == REQUEST_TRUST_EXTERNAL_SOURCE && result == RESULT_OK) {
            initiateInstall();
        } else {
            finish();
        }
    }

    private String getPackageNameForUid(int sourceUid) {
        String[] packagesForUid = mPm.getPackagesForUid(sourceUid);
        if (packagesForUid == null) {
            return null;
        }
        if (packagesForUid.length > 1) {
            if (mCallingPackage != null) {
                for (String packageName : packagesForUid) {
                    if (packageName.equals(mCallingPackage)) {
                        return packageName;
                    }
                }
            }
            Log.i(TAG, "Multiple packages found for source uid " + sourceUid);
        }
        return packagesForUid[0];
    }

    private boolean isInstallRequestFromUnknownSource(Intent intent) {
        if (mCallingPackage != null && intent.getBooleanExtra(
                Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)) {
            if (mSourceInfo != null) {
                if ((mSourceInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
                        != 0) {
                    // Privileged apps are not considered an unknown source.
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return whether the device admin restricts installation from unknown sources
     */
    private boolean isUnknownSourcesDisallowed() {
        return mUserManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
    }

    private void initiateInstall() {
        String pkgName = mPkgInfo.packageName;
        // Check if there is already a package on the device with this name
        // but it has been renamed to something else.
        String[] oldName = mPm.canonicalToCurrentPackageNames(new String[] { pkgName });
        if (oldName != null && oldName.length > 0 && oldName[0] != null) {
            pkgName = oldName[0];
            mPkgInfo.packageName = pkgName;
            mPkgInfo.applicationInfo.packageName = pkgName;
        }
        // Check if package is already installed. display confirmation dialog if replacing pkg
        try {
            // This is a little convoluted because we want to get all uninstalled
            // apps, but this may include apps with just data, and if it is just
            // data we still want to count it as "installed".
            mAppInfo = mPm.getApplicationInfo(pkgName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES);
            if ((mAppInfo.flags&ApplicationInfo.FLAG_INSTALLED) == 0) {
                mAppInfo = null;
            }
        } catch (NameNotFoundException e) {
            mAppInfo = null;
        }

        startInstallConfirm();
    }

    void setPmResult(int pmResult) {
        Intent result = new Intent();
        result.putExtra(Intent.EXTRA_INSTALL_RESULT, pmResult);
        setResult(pmResult == PackageManager.INSTALL_SUCCEEDED
                ? RESULT_OK : RESULT_FIRST_USER, result);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mPm = getPackageManager();
        mIpm = AppGlobals.getPackageManager();
        mAppOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        mInstaller = mPm.getPackageInstaller();
        mUserManager = (UserManager) getSystemService(Context.USER_SERVICE);

        final Intent intent = getIntent();

        mCallingPackage = intent.getStringExtra(EXTRA_CALLING_PACKAGE);
        mSourceInfo = intent.getParcelableExtra(EXTRA_ORIGINAL_SOURCE_INFO);
        mOriginatingUid = intent.getIntExtra(Intent.EXTRA_ORIGINATING_UID,
                VerificationParams.NO_UID);
        mOriginatingPackage = (mOriginatingUid != VerificationParams.NO_UID) ? getPackageNameForUid(
                mOriginatingUid) : null;


        final Uri packageUri;

        if (PackageInstaller.ACTION_CONFIRM_PERMISSIONS.equals(intent.getAction())) {
            final int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            final PackageInstaller.SessionInfo info = mInstaller.getSessionInfo(sessionId);
            if (info == null || !info.sealed || info.resolvedBaseCodePath == null) {
                Log.w(TAG, "Session " + mSessionId + " in funky state; ignoring");
                finish();
                return;
            }

            mSessionId = sessionId;
            packageUri = Uri.fromFile(new File(info.resolvedBaseCodePath));
            mOriginatingURI = null;
            mReferrerURI = null;
        } else {
            mSessionId = -1;
            packageUri = intent.getData();
            mOriginatingURI = intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI);
            mReferrerURI = intent.getParcelableExtra(Intent.EXTRA_REFERRER);
        }

        // if there's nothing to do, quietly slip into the ether
        if (packageUri == null) {
            Log.w(TAG, "Unspecified source");
            setPmResult(PackageManager.INSTALL_FAILED_INVALID_URI);
            finish();
            return;
        }

        if (DeviceUtils.isWear(this)) {
            showDialogInner(DLG_NOT_SUPPORTED_ON_WEAR);
            return;
        }

        boolean wasSetUp = processPackageUri(packageUri);
        if (!wasSetUp) {
            return;
        }

        // load dummy layout with OK button disabled until we override this layout in
        // startInstallConfirm
        bindUi(R.layout.install_confirm, false);
        checkIfAllowedAndInitiateInstall();
    }

    private void bindUi(int layout, boolean enableOk) {
        setContentView(layout);

        mOk = (Button) findViewById(R.id.ok_button);
        mCancel = (Button)findViewById(R.id.cancel_button);
        mOk.setOnClickListener(this);
        mCancel.setOnClickListener(this);

        if (!enableOk) {
            mOk.setEnabled(false);
        }

        PackageUtil.initSnippetForNewApp(this, mAppSnippet, R.id.app_snippet);
    }

    /**
     * Check if it is allowed to install the package and initiate install if allowed. If not allowed
     * show the appropriate dialog.
     */
    private void checkIfAllowedAndInitiateInstall() {
        if (!isInstallRequestFromUnknownSource(getIntent())) {
            initiateInstall();
            return;
        }
        // If the admin prohibits it, just show error and exit.
        if (isUnknownSourcesDisallowed()) {
            if ((mUserManager.getUserRestrictionSource(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                    Process.myUserHandle()) & UserManager.RESTRICTION_SOURCE_SYSTEM) != 0) {
                // Someone set user restriction via UserManager#setUserRestriction. We don't want to
                // break apps that might already be doing this
                showDialogInner(DLG_UNKNOWN_SOURCES_RESTRICTED_FOR_USER);
                return;
            } else {
                startActivity(new Intent(Settings.ACTION_SHOW_ADMIN_SUPPORT_DETAILS));
                finish();
            }
        } else {
            handleUnknownSources();
        }
    }

    private void handleUnknownSources() {
        if (mOriginatingPackage == null) {
            Log.e(TAG, "No source package name for external install request. Aborting install");
            finish();
            return;
        }
        int appOpMode = mAppOpsManager.checkOpNoThrow(AppOpsManager.OP_REQUEST_INSTALL_PACKAGES,
                mOriginatingUid, mOriginatingPackage);
        switch (appOpMode) {
            case AppOpsManager.MODE_DEFAULT:
                try {
                    int result = mIpm.checkUidPermission(
                            Manifest.permission.REQUEST_INSTALL_PACKAGES, mOriginatingUid);
                    if (result == PackageManager.PERMISSION_GRANTED) {
                        initiateInstall();
                        break;
                    }
                } catch (RemoteException exc) {
                    Log.e(TAG, "Unable to talk to package manager");
                }
                mAppOpsManager.setMode(AppOpsManager.OP_REQUEST_INSTALL_PACKAGES, mOriginatingUid,
                        mOriginatingPackage, AppOpsManager.MODE_ERRORED);
                // fall through
            case AppOpsManager.MODE_ERRORED:
                showDialogInner(DLG_EXTERNAL_SOURCE_BLOCKED);
                break;
            case AppOpsManager.MODE_ALLOWED:
                initiateInstall();
                break;
            default:
                Log.e(TAG, "Invalid app op mode " + appOpMode
                        + " for OP_REQUEST_INSTALL_PACKAGES found for uid " + mOriginatingUid);
                finish();
                break;
        }
    }

    /**
     * Parse the Uri and set up the installer for this package.
     *
     * @param packageUri The URI to parse
     *
     * @return {@code true} iff the installer could be set up
     */
    private boolean processPackageUri(final Uri packageUri) {
        mPackageURI = packageUri;

        final String scheme = packageUri.getScheme();

        switch (scheme) {
            case SCHEME_PACKAGE: {
                try {
                    mPkgInfo = mPm.getPackageInfo(packageUri.getSchemeSpecificPart(),
                            PackageManager.GET_PERMISSIONS
                                    | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                } catch (NameNotFoundException e) {
                }
                if (mPkgInfo == null) {
                    Log.w(TAG, "Requested package " + packageUri.getScheme()
                            + " not available. Discontinuing installation");
                    showDialogInner(DLG_PACKAGE_ERROR);
                    setPmResult(PackageManager.INSTALL_FAILED_INVALID_APK);
                    return false;
                }
                mAppSnippet = new PackageUtil.AppSnippet(mPm.getApplicationLabel(mPkgInfo.applicationInfo),
                        mPm.getApplicationIcon(mPkgInfo.applicationInfo));
            } break;

            case SCHEME_FILE: {
                File sourceFile = new File(packageUri.getPath());
                PackageParser.Package parsed = PackageUtil.getPackageInfo(sourceFile);

                // Check for parse errors
                if (parsed == null) {
                    Log.w(TAG, "Parse error when parsing manifest. Discontinuing installation");
                    showDialogInner(DLG_PACKAGE_ERROR);
                    setPmResult(PackageManager.INSTALL_FAILED_INVALID_APK);
                    return false;
                }
                mPkgInfo = PackageParser.generatePackageInfo(parsed, null,
                        PackageManager.GET_PERMISSIONS, 0, 0, null,
                        new PackageUserState());
                mAppSnippet = PackageUtil.getAppSnippet(this, mPkgInfo.applicationInfo, sourceFile);
            } break;

            default: {
                Log.w(TAG, "Unsupported scheme " + scheme);
                setPmResult(PackageManager.INSTALL_FAILED_INVALID_URI);
                finish();
                return false;
            }
        }

        return true;
    }

    @Override
    public void onBackPressed() {
        if (mSessionId != -1) {
            mInstaller.setPermissionsResult(mSessionId, false);
        }
        super.onBackPressed();
    }

    // Generic handling when pressing back key
    public void onCancel(DialogInterface dialog) {
        finish();
    }

    public void onClick(View v) {
        if (v == mOk) {
            if (mOk.isEnabled()) {
                if (mOkCanInstall || mScrollView == null) {
                    if (mSessionId != -1) {
                        mInstaller.setPermissionsResult(mSessionId, true);
                        finish();
                    } else {
                        startInstall();
                    }
                } else {
                    mScrollView.pageScroll(View.FOCUS_DOWN);
                }
            }
        } else if (v == mCancel) {
            // Cancel and finish
            setResult(RESULT_CANCELED);
            if (mSessionId != -1) {
                mInstaller.setPermissionsResult(mSessionId, false);
            }
            finish();
        }
    }

    private void startInstall() {
        // Start subactivity to actually install the application
        Intent newIntent = new Intent();
        newIntent.putExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO,
                mPkgInfo.applicationInfo);
        newIntent.setData(mPackageURI);
        newIntent.setClass(this, InstallInstalling.class);
        String installerPackageName = getIntent().getStringExtra(
                Intent.EXTRA_INSTALLER_PACKAGE_NAME);
        if (mOriginatingURI != null) {
            newIntent.putExtra(Intent.EXTRA_ORIGINATING_URI, mOriginatingURI);
        }
        if (mReferrerURI != null) {
            newIntent.putExtra(Intent.EXTRA_REFERRER, mReferrerURI);
        }
        if (mOriginatingUid != VerificationParams.NO_UID) {
            newIntent.putExtra(Intent.EXTRA_ORIGINATING_UID, mOriginatingUid);
        }
        if (installerPackageName != null) {
            newIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME,
                    installerPackageName);
        }
        if (getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
            newIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        }
        if(localLOGV) Log.i(TAG, "downloaded app uri="+mPackageURI);
        startActivity(newIntent);
        finish();
    }
}
