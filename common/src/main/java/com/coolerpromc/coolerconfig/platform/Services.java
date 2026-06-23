package com.coolerpromc.coolerconfig.platform;

import com.coolerpromc.coolerconfig.Constants;
import com.coolerpromc.coolerconfig.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/**
 * Locates the loader-specific implementations that the common code depends on.
 *
 * <p>The common module cannot reference Fabric or NeoForge classes directly, so it declares the
 * behaviour it needs as an interface ({@link IPlatformHelper}) and looks the implementation up at
 * runtime with {@link ServiceLoader}. Each loader module ships a
 * {@code META-INF/services/com.coolerpromc.coolerconfig.platform.services.IPlatformHelper} file
 * naming its own implementation.
 *
 * <p>Internal — not part of the public API.
 */
public final class Services {

    /**
     * The platform helper for the loader currently running: config directory, physical side,
     * and mod-loaded queries.
     */
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {}

    /**
     * Loads the single implementation of {@code clazz} provided by the running loader.
     *
     * @throws IllegalStateException if no implementation is registered — which means the loader
     *                               module was not on the classpath, and nothing that depends on
     *                               it could work anyway
     */
    public static <T> T load(Class<T> clazz) {
        T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
