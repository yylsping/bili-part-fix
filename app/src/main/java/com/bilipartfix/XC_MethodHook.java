package com.bilipartfix;

import java.util.HashMap;
import java.util.Map;

/** Minimal before/after callback adapter backed by libxposed's interceptor chain. */
abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    static final class MethodHookParam {
        Object thisObject;
        Object[] args;
        private Object result;
        private final Map<String, Object> extras = new HashMap<>();

        Object getResult() {
            return result;
        }

        void setResult(Object value) {
            result = value;
        }

        void setObjectExtra(String key, Object value) {
            extras.put(key, value);
        }

        Object getObjectExtra(String key) {
            return extras.get(key);
        }
    }
}
