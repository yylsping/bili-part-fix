package io.github.yylsping.bilipartfix;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Exposes 7.42.0's existing image save operation through its native action menu. */
final class ImageSaveFix {
    private static final String FRAGMENT = "com.bilibili.lib.imageviewer.fragment.ImageFragment";
    private static final String SUPER_MENU = "com.bilibili.app.comm.supermenu.SuperMenu";
    private static final String MENU_BUILDER = "com.bilibili.app.comm.supermenu.core.a";
    private static final String SAVE = "save_image";

    private ImageSaveFix() {}

    static boolean install(ClassLoader loader) {
        try {
            Class<?> fragmentType = XposedHelpers.findClass(FRAGMENT, loader);
            Class<?> menuType = XposedHelpers.findClass(SUPER_MENU, loader);
            Class<?> builderType = XposedHelpers.findClass(MENU_BUILDER, loader);
            Class<?> listenerType = XposedHelpers.findClass(SUPER_MENU + "$a", loader);
            Method save = fragmentType.getDeclaredMethod("Xt");
            save.setAccessible(true);
            XposedHelpers.findAndHookMethod(fragmentType, "onViewCreated", View.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            // Subclasses (e.g. avatar editing) retain their own interactions.
                            if (param.thisObject.getClass() != fragmentType) return;
                            try {
                                View image = (View) XposedHelpers.getObjectField(param.thisObject, "b");
                                if (image == null || image.isLongClickable()) return;
                                WeakReference<Object> reference = new WeakReference<>(param.thisObject);
                                image.setOnLongClickListener(view -> {
                                    try {
                                        Object fragment = reference.get();
                                        Activity activity = activityOf(fragment);
                                        if (activity == null) return false;
                                        int icon = activity.getResources().getIdentifier(
                                                "ic_super_menu_download", "drawable", activity.getPackageName());
                                        Object builder = XposedHelpers.newInstance(builderType, activity);
                                        XposedHelpers.callMethod(builder, "c", SAVE, icon, "保存图片");
                                        Object listener = Proxy.newProxyInstance(loader,
                                                new Class<?>[]{listenerType}, (proxy, method, args) -> {
                                                    if (method.getDeclaringClass() == Object.class) {
                                                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                                                        if (method.getName().equals("equals")) return proxy == args[0];
                                                        return "BiliPartFixImageSave";
                                                    }
                                                    if (args != null && args.length == 1 && args[0] != null
                                                            && SAVE.equals(XposedHelpers.callMethod(args[0], "getItemId"))) {
                                                        Object current = reference.get();
                                                        try {
                                                            if (activityOf(current) != null) save.invoke(current);
                                                        } catch (Throwable failure) {
                                                            XposedBridge.log("native image save failed", failure);
                                                            Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_SHORT).show();
                                                        }
                                                    }
                                                    return false;
                                                });
                                        Object menu = XposedHelpers.callStaticMethod(menuType, "with", activity);
                                        XposedHelpers.callMethod(menu, "addMenus", XposedHelpers.callMethod(builder, "build"));
                                        XposedHelpers.callMethod(menu, "itemClickListener", listener);
                                        XposedHelpers.callMethod(menu, "scene", "comment");
                                        XposedHelpers.callMethod(menu, "show");
                                        return true;
                                    } catch (Throwable failure) {
                                        XposedBridge.log("image save menu failed", failure);
                                        return false;
                                    }
                                });
                            } catch (Throwable failure) {
                                XposedBridge.log("image long-press binding failed", failure);
                            }
                        }
                    });
            XposedBridge.log("native image save menu hook installed for 7420400");
            return true;
        } catch (Throwable failure) {
            XposedBridge.log("image save menu hook installation failed", failure);
            return false;
        }
    }

    private static Activity activityOf(Object fragment) {
        if (fragment == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(fragment, "isAdded"))) return null;
        Activity activity = (Activity) XposedHelpers.callMethod(fragment, "getActivity");
        return activity == null || activity.isFinishing() || activity.isDestroyed() ? null : activity;
    }
}
