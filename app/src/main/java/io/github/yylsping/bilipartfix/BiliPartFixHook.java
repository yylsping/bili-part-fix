package io.github.yylsping.bilipartfix;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Modern libxposed API 102 entry point for the Bilibili 7.4.0 compatibility fixes. */
public final class BiliPartFixHook extends XposedModule {
    private static final String TARGET_PACKAGE = "tv.danmaku.bili";
    private static final String WEB_PROCESS = "tv.danmaku.bili:web";
    private static final long TARGET_VERSION_CODE = 7040300L;
    private static final String DATA_SOURCE_CLASS =
            "tv.danmaku.bili.videopage.player.datasource.b";
    private static final String DETAIL_CLASS =
            "tv.danmaku.bili.videopage.data.view.model.BiliVideoDetail";
    private static final String EXTRA_ORIGINAL_SEASON =
            "io.github.yylsping.bilipartfix.originalUgcSeason";

    private final AtomicBoolean installed = new AtomicBoolean(false);
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
                        if (versionCode != TARGET_VERSION_CODE) {
                            XposedBridge.log("unsupported tv.danmaku.bili versionCode="
                                    + versionCode + ", expected=" + TARGET_VERSION_CODE);
                            detach();
                            return;
                        }
                        if (!installed.compareAndSet(false, true)) return;
                        if (mainProcess) {
                            installDataSourceFix(classLoader);
                            DynamicCommentFix.install(classLoader);
                            CommentImageFix.install(classLoader);
                            SmallStationPostFix.install(classLoader);
                            ActivityResumeCoordinator.install();
                        } else {
                            Eva3ArticleFix.install(classLoader);
                        }
                    }
                });
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

    private void installDataSourceFix(ClassLoader classLoader) {
        try {
            Class<?> detailClass = XposedHelpers.findClass(DETAIL_CLASS, classLoader);
            Class<?> dataSourceClass = XposedHelpers.findClass(DATA_SOURCE_CLASS, classLoader);
            XposedHelpers.findAndHookMethod(dataSourceClass, "w1", detailClass, Bundle.class,
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
            XposedBridge.log("modern API 102 hooks installed for Bilibili 7.4.0");
        } catch (Throwable throwable) {
            installed.set(false);
            XposedBridge.log("data-source hook installation failed", throwable);
        }
    }
}
