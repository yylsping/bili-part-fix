package io.github.yylsping.bilipartfix;

import java.util.List;

/** Compatibility bridge for modern dynamics returned to Bilibili 7.4.0. */
final class DynamicCommentFix {
    private static final String VIEW_MODEL =
            "com.bilibili.bplus.followinglist.detail.DynamicDetailViewModel";
    private static final String DETAIL_FRAGMENT =
            "com.bilibili.bplus.followinglist.detail.DynamicDetailFragment";
    private static final String COMPOSE_ACTIVITY = "com.bilibili.lib.ui.ComposeActivity";
    private static final long MAX_BUSINESS_ID = 999_999_999_999L;

    private DynamicCommentFix() {}

    static void install(ClassLoader classLoader) {
        try {
            Class<?> viewModel = XposedHelpers.findClass(VIEW_MODEL, classLoader);
            Class<?> fragmentActivity = XposedHelpers.findClass(
                    "androidx.fragment.app.FragmentActivity", classLoader);

            // Fast path for a newly constructed detail page.
            XposedHelpers.findAndHookMethod(viewModel, "D2", fragmentActivity,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            repairDescriptor(param.thisObject, param.getResult());
                        }
                    });

            XposedBridge.log("BiliPartFix: dynamic comment compatibility hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: dynamic comment hook installation failed: "
                    + throwable);
        }
    }

    static void onActivityResumed(Object activity) {
        if (!COMPOSE_ACTIVITY.equals(activity.getClass().getName())) return;
        RetryScheduler.schedule(activity, "dynamic-comments",
                new long[]{300L, 900L, 2200L}, DynamicCommentFix::repairActivity);
    }

    private static boolean repairActivity(Object activity) {
        try {
            Object manager = XposedHelpers.callMethod(activity, "getSupportFragmentManager");
            Object value = XposedHelpers.callMethod(manager, "getFragments");
            if (!(value instanceof List)) return false;
            for (Object fragment : (List<?>) value) {
                if (fragment != null && DETAIL_FRAGMENT.equals(fragment.getClass().getName())) {
                    return repairRestoredFragment(fragment);
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log("dynamic comment activity repair failed", throwable);
        }
        return false;
    }

    private static boolean repairRestoredFragment(Object fragment) {
        try {
            // These are the real dex field names; JADX displays them as f80153m/f80144d.
            Object viewModel = XposedHelpers.getObjectField(fragment, "m");
            Object descriptor = XposedHelpers.getObjectField(fragment, "d");
            if (descriptor == null) return false;
            if (!needsRepair(descriptor)) return true;
            long businessId = getBusinessId(viewModel);
            if (businessId <= 0) return false;

            // D2's type-2 branch reads ViewModel.p. Repair both the source field
            // and the already-created page descriptor, then rebuild the 3-tab pager.
            XposedHelpers.setLongField(viewModel, "p", businessId);
            XposedHelpers.callMethod(descriptor, "o", businessId, 11);
            XposedHelpers.callMethod(fragment, "tt");
            XposedBridge.log("repaired restored dynamic comments oid=" + businessId);
            return true;
        } catch (Throwable throwable) {
            XposedBridge.log("restored dynamic comment repair failed", throwable);
            return false;
        }
    }

    private static void repairDescriptor(Object viewModel, Object descriptor) {
        if (!needsRepair(descriptor)) return;
        try {
            long businessId = getBusinessId(viewModel);
            if (businessId <= 0) return;
            XposedHelpers.setLongField(viewModel, "p", businessId);
            // Real dex names are f/k; JADX displays f77279f/f77284k.
            XposedHelpers.setLongField(descriptor, "f", businessId);
            XposedHelpers.setIntField(descriptor, "k", 11);
            XposedBridge.log("BiliPartFix: repaired dynamic comments oid=" + businessId);
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: dynamic comment descriptor repair failed: "
                    + throwable);
        }
    }

    private static boolean needsRepair(Object descriptor) {
        if (descriptor == null) return false;
        try {
            return ((Integer) XposedHelpers.callMethod(descriptor, "h")) == 11
                    && ((Long) XposedHelpers.callMethod(descriptor, "f")) == 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long getBusinessId(Object viewModel) {
        try {
            Object model = XposedHelpers.callMethod(viewModel, "l2");
            Object extend = model == null ? null : XposedHelpers.callMethod(model, "d");
            Object raw = extend == null ? null : XposedHelpers.callMethod(extend, "b");
            if (!(raw instanceof String)) return -1L;
            long value = Long.parseLong((String) raw);
            return value > 0 && value <= MAX_BUSINESS_ID ? value : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }
}
