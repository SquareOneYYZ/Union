package org.traccar.storage;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectionCache {
    private static final ConcurrentHashMap<String, MethodHandle> CACHE =
            new ConcurrentHashMap<>();

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private ReflectionCache() {
    }


    public static MethodHandle get(Method method) {
        String key = method.getDeclaringClass().getName()
                + '#' + method.getName()
                + '#' + method.getReturnType().getName();
        return CACHE.computeIfAbsent(key, k -> {
            try {
                method.setAccessible(true);
                return LOOKUP.unreflect(method);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("ReflectionCache: cannot unreflect " + method, e);
            }
        });
    }

    public static int size() {
        return CACHE.size();
    }

    static void clear() {
        CACHE.clear();
    }
}
