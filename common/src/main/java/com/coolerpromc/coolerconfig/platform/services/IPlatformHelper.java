package com.coolerpromc.coolerconfig.platform.services;

import java.nio.file.Path;

public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    /** Returns the loader's config directory (e.g. {@code .minecraft/config}). */
    Path getConfigDirectory();

    /** Returns {@code true} when running on the physical client (not a dedicated server). */
    boolean isPhysicalClient();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}