package io.github.yylsping.bilipartfix;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/** Small process-local facade used while keeping the individual fixes easy to audit. */
final class XposedBridge {
    private static final String TAG = "BiliPartFix";
    private static volatile XposedModule module;

    private XposedBridge() {}

    static void attach(XposedModule value) {
        module = value;
        XposedHelpers.attach(value);
    }

    static void log(String message) {
        XposedModule current = module;
        if (current != null) current.log(Log.INFO, TAG, message);
        else Log.i(TAG, message);
    }

    static void log(String message, Throwable throwable) {
        XposedModule current = module;
        if (current != null) current.log(Log.ERROR, TAG, message, throwable);
        else Log.e(TAG, message, throwable);
    }

    static void hookAllMethods(Class<?> type, String name, XC_MethodHook callback) {
        boolean found = false;
        for (Method method : type.getDeclaredMethods()) {
            if (!name.equals(method.getName())) continue;
            XposedHelpers.hookExecutable(method, callback);
            found = true;
        }
        if (!found) throw new IllegalArgumentException(type.getName() + '#' + name);
    }

    static void hookAllConstructors(Class<?> type, XC_MethodHook callback) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length == 0) throw new IllegalArgumentException(type.getName());
        for (Constructor<?> constructor : constructors) {
            XposedHelpers.hookExecutable(constructor, callback);
        }
    }
}
