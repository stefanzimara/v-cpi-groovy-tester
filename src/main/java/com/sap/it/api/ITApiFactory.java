package com.sap.it.api;

import java.util.HashMap;
import java.util.Map;

/**
 * Lokaler Nachbau von com.sap.it.api.ITApiFactory.
 *
 * Der Runner registriert die verfuegbaren Services vor jedem Lauf, damit
 * Scripte wie
 *   def service = ITApiFactory.getService(SecureStoreService.class, null)
 * lokal funktionieren.
 */
public final class ITApiFactory {

    private static final Map<Class<?>, Object> SERVICES = new HashMap<Class<?>, Object>();

    private ITApiFactory() {
    }

    public static synchronized <T> void registerService(Class<T> type, T instance) {
        SERVICES.put(type, instance);
    }

    public static synchronized void reset() {
        SERVICES.clear();
    }

    @SuppressWarnings("unchecked")
    public static synchronized <T> T getService(Class<T> type, Object parameter) {
        return (T) SERVICES.get(type);
    }
}
