package io.github.yylsping.bilipartfix;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;

import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Modern libxposed API 102 entry point for the Bilibili compatibility fixes.
 * Supported hosts: 7.4.0 (7040300) and 7.42.0 (7420400). The 7.42.0 build
 * installs the multipart, watch-later, charge-trial and image-save fixes plus
 * the decoder mode entry and Smart Auto policy via the 7420400 DecoderProfile.
 */
public final class BiliPartFixHook extends XposedModule {
    private static final String TARGET_PACKAGE = "tv.danmaku.bili";
    private static final String WEB_PROCESS = "tv.danmaku.bili:web";
    private static final String DATA_SOURCE_CLASS =
            "tv.danmaku.bili.videopage.player.datasource.b";
    private static final String DETAIL_CLASS =
            "tv.danmaku.bili.videopage.data.view.model.BiliVideoDetail";
    private static final String EXTRA_ORIGINAL_SEASON =
            "io.github.yylsping.bilipartfix.originalUgcSeason";

    private final InstallState installState = new InstallState();
    private String processName = "";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        XposedBridge.attach(this);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        boolean mainProcess = TARGET_PACKAGE.equals(processName);
        boolean webProcess = WEB_PROCESS.equals(processName);
        if (!mainProcess && !webProcess) {
            detach();
            return;
        }

        ClassLoader classLoader = param.getClassLoader();
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam hookParam) {
                        Context context = (Context) hookParam.args[0];
                        long versionCode = getVersionCode(context);
                        String versionName = getVersionName(context);
                        HostVersion host = HostVersion.from(versionCode);
                        if (!host.isSupported()) {
                            XposedBridge.log("unsupported tv.danmaku.bili versionCode="
                                    + versionCode + " versionName=" + versionName);
                            detach();
                            return;
                        }
                        if (!installState.beginInitialization()) return;
                        XposedBridge.log("host recognized as " + host + " (versionCode="
                                + versionCode + ", versionName=" + versionName + ")");
                        if (mainProcess) {
                            installMainProcess(context, classLoader, host);
                        } else if (host == HostVersion.V7040300) {
                            Eva3ArticleFix.install(classLoader);
                        }
                    }
                });
    }

    private void installMainProcess(Context context, ClassLoader classLoader, HostVersion host) {
        boolean multipart = installDataSourceFix(classLoader, host);
        if (!host.installsLegacyFixes()) {
            // Each independently reproduced compatibility issue has its own
            // installer. Leave native content rendering and browsing intact;
            // a failure of one feature must not mask the others.
            boolean watchLater = WatchLaterFix.install(classLoader);
            boolean trialPreview = TrialPreviewFix.install(classLoader);
            boolean imageSave = ImageSaveFix.install(classLoader);
            CodecInstallResult codecResult = installCodecFixes(context, classLoader, host);
            XposedBridge.log(FeatureInstallSummary.of7420400(multipart, watchLater,
                    codecResult.codecInstalled, codecResult.settingsInstalled, trialPreview, imageSave));
            return;
        }
        if (!multipart) {
            XposedBridge.log("7040300 feature results: multipart=FAILED; "
                    + "continuing with the remaining fixes");
        }
        DynamicCommentFix.install(classLoader);
        WatchLaterFix.install(classLoader);
        PlayerUniteFix.install(classLoader);
        installCodecFixes(context, classLoader, host);
        CommentImageFix.install(classLoader);
        SmallStationPostFix.install(classLoader);
        ActivityResumeCoordinator.install();
    }

    private CodecInstallResult installCodecFixes(Context context, ClassLoader classLoader,
                                                 HostVersion host) {
        boolean codecInstalled;
        try {
            codecInstalled = PlayerCodecFix.install(context, classLoader, host);
        } catch (Throwable throwable) {
            XposedBridge.log("player codec policy installation failed", throwable);
            codecInstalled = false;
        }
        boolean settingsInstalled;
        try {
            settingsInstalled = SettingsInjection.install(context, classLoader, host);
        } catch (Throwable throwable) {
            XposedBridge.log("settings injection installation failed", throwable);
            settingsInstalled = false;
        }
        return new CodecInstallResult(codecInstalled, settingsInstalled);
    }

    private static final class CodecInstallResult {
        final boolean codecInstalled;
        final boolean settingsInstalled;

        CodecInstallResult(boolean codecInstalled, boolean settingsInstalled) {
            this.codecInstalled = codecInstalled;
            this.settingsInstalled = settingsInstalled;
        }
    }

    private static long getVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.getLongVersionCode();
            //noinspection deprecation
            return info.versionCode;
        } catch (Throwable throwable) {
            XposedBridge.log("unable to read target version", throwable);
            return -1L;
        }
    }

    private static String getVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Throwable throwable) {
            return "?";
        }
    }

    private boolean installDataSourceFix(ClassLoader classLoader, HostVersion host) {
        try {
            Class<?> detailClass = XposedHelpers.findClass(DETAIL_CLASS, classLoader);
            Class<?> dataSourceClass = XposedHelpers.findClass(DATA_SOURCE_CLASS, classLoader);
            XposedHelpers.findAndHookMethod(dataSourceClass, host.dataSourceRouterMethod(),
                    detailClass, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object detail = param.args[0];
                            if (detail == null) return;
                            try {
                                Object season = XposedHelpers.getObjectField(detail, "ugcSeason");
                                Object pages = XposedHelpers.getObjectField(detail, "mPageList");
                                if (season != null && pages instanceof List
                                        && ((List<?>) pages).size() > 1) {
                                    param.setObjectExtra(EXTRA_ORIGINAL_SEASON, season);
                                    XposedHelpers.setObjectField(detail, "ugcSeason", null);
                                    XposedBridge.log("routed multi-page UGC-season video to "
                                            + "normal multipart data source; pages="
                                            + ((List<?>) pages).size());
                                }
                            } catch (Throwable throwable) {
                                XposedBridge.log("before-hook failed", throwable);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object originalSeason =
                                    param.getObjectExtra(EXTRA_ORIGINAL_SEASON);
                            if (originalSeason == null || param.args[0] == null) return;
                            try {
                                XposedHelpers.setObjectField(
                                        param.args[0], "ugcSeason", originalSeason);
                            } catch (Throwable throwable) {
                                XposedBridge.log("failed to restore ugcSeason", throwable);
                            }
                        }
                    });
            XposedBridge.log("data-source hook installed for " + host
                    + " via " + host.dataSourceRouterMethod());
            return true;
        } catch (Throwable throwable) {
            // A single feature failure only records itself: it must not reset
            // the module-wide one-way initialization state, and must not block
            // other features from installing.
            XposedBridge.log("data-source hook installation failed", throwable);
            return false;
        }
    }
}
