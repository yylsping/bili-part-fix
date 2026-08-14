package io.github.yylsping.bilipartfix;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/** Cached standard-reflection helpers and hook adapters for Modern API 102. */
final class XposedHelpers {
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Constructor<?>> CONSTRUCTORS = new ConcurrentHashMap<>();
    private static volatile XposedModule module;

    private XposedHelpers() {}

    static void attach(XposedModule value) {
        module = value;
    }

    static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static void findAndHookMethod(Class<?> type, String name, Object... signature) {
        if (signature.length == 0 || !(signature[signature.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("callback missing for " + type.getName() + '#' + name);
        }
        Class<?>[] parameters = new Class<?>[signature.length - 1];
        for (int i = 0; i < parameters.length; i++) parameters[i] = (Class<?>) signature[i];
        Method method = findExactMethod(type, name, parameters);
        hookExecutable(method, (XC_MethodHook) signature[signature.length - 1]);
    }

    static void findAndHookMethod(String className, ClassLoader classLoader, String name,
                                  Object... signature) {
        findAndHookMethod(findClass(className, classLoader), name, signature);
    }

    static void hookExecutable(Executable executable, XC_MethodHook callback) {
        XposedModule current = module;
        if (current == null) throw new IllegalStateException("modern bridge not attached");
        executable.setAccessible(true);
        String id = "bilipartfix:" + executable.getDeclaringClass().getName() + ':'
                + executable.getName() + ':' + Arrays.toString(executable.getParameterTypes())
                + ':' + callback.getClass().getName();
        current.hook(executable).setId(id).intercept(chain -> {
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.thisObject = chain.getThisObject();
            param.args = chain.getArgs().toArray();
            try {
                callback.beforeHookedMethod(param);
            } catch (Throwable throwable) {
                XposedBridge.log("before callback failed for " + executable, throwable);
            }
            Object result = null;
            Throwable originalFailure = null;
            try {
                result = chain.proceed(param.args);
            } catch (Throwable throwable) {
                originalFailure = throwable;
            }
            param.setResult(result);
            try {
                callback.afterHookedMethod(param);
            } catch (Throwable throwable) {
                XposedBridge.log("after callback failed for " + executable, throwable);
            }
            if (originalFailure != null) throw originalFailure;
            return param.getResult();
        });
    }

    static Object getObjectField(Object target, String name) {
        try {
            return findField(target.getClass(), name).get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Object getStaticObjectField(Class<?> type, String name) {
        try {
            return findField(type, name).get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static void setObjectField(Object target, String name, Object value) {
        try {
            findField(target.getClass(), name).set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static long getLongField(Object target, String name) {
        try {
            return findField(target.getClass(), name).getLong(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static int getIntField(Object target, String name) {
        try {
            return findField(target.getClass(), name).getInt(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static void setLongField(Object target, String name, long value) {
        try {
            findField(target.getClass(), name).setLong(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static void setIntField(Object target, String name, int value) {
        try {
            findField(target.getClass(), name).setInt(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Object callMethod(Object target, String name, Object... args) {
        try {
            return findCompatibleMethod(target.getClass(), name, false, args).invoke(target, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Object callStaticMethod(Class<?> type, String name, Object... args) {
        try {
            return findCompatibleMethod(type, name, true, args).invoke(null, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Object newInstance(Class<?> type, Object... args) {
        try {
            String key = executableKey(type, "<init>", args);
            Constructor<?> constructor = CONSTRUCTORS.get(key);
            if (constructor == null) {
                for (Constructor<?> candidate : type.getDeclaredConstructors()) {
                    if (compatible(candidate.getParameterTypes(), args)) {
                        candidate.setAccessible(true);
                        constructor = candidate;
                        CONSTRUCTORS.put(key, candidate);
                        break;
                    }
                }
            }
            if (constructor == null) throw new NoSuchMethodException(key);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        String key = type.getName() + '#' + name;
        Field cached = FIELDS.get(key);
        if (cached != null) return cached;
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                FIELDS.put(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(key);
    }

    private static Method findExactMethod(Class<?> type, String name, Class<?>[] parameters) {
        String key = type.getName() + '#' + name + Arrays.toString(parameters);
        Method cached = METHODS.get(key);
        if (cached != null) return cached;
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                METHODS.put(key, method);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException(new NoSuchMethodException(key));
    }

    private static Method findCompatibleMethod(Class<?> type, String name, boolean requireStatic,
                                               Object[] args) throws NoSuchMethodException {
        String key = executableKey(type, (requireStatic ? "static:" : "") + name, args);
        Method cached = METHODS.get(key);
        if (cached != null) return cached;
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!name.equals(method.getName())
                        || (requireStatic && !Modifier.isStatic(method.getModifiers()))
                        || !compatible(method.getParameterTypes(), args)) continue;
                method.setAccessible(true);
                METHODS.put(key, method);
                return method;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(key);
    }

    private static boolean compatible(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] == null) {
                if (parameters[i].isPrimitive()) return false;
                continue;
            }
            Class<?> parameter = wrap(parameters[i]);
            if (!parameter.isAssignableFrom(args[i].getClass())) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    private static String executableKey(Class<?> type, String name, Object[] args) {
        StringBuilder key = new StringBuilder(type.getName()).append('#').append(name).append('(');
        for (Object arg : args) key.append(arg == null ? "null" : arg.getClass().getName()).append(',');
        return key.append(')').toString();
    }
}
