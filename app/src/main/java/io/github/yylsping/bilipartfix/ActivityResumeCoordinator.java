package io.github.yylsping.bilipartfix;

import android.app.Activity;

/** One main-process lifecycle hook shared by the two dynamic-detail repairs. */
final class ActivityResumeCoordinator {
    private ActivityResumeCoordinator() {}

    static void install() {
        XposedBridge.hookAllMethods(Activity.class, "performResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object activity = param.thisObject;
                DynamicCommentFix.onActivityResumed(activity);
                SmallStationPostFix.onActivityResumed(activity);
            }
        });
        XposedBridge.log("shared dynamic-detail lifecycle coordinator installed");
    }
}
