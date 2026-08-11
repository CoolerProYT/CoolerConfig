package com.coolerpromc.coolerconfig;

import com.coolerpromc.coolerconfig.platform.Services;

/**
 * Entry point of the CoolerConfig library mod.
 *
 * <p>Dependent mods do not call anything here — see
 * {@link com.coolerpromc.coolerconfig.config.ConfigSpec ConfigSpec} for the public API. This
 * class is invoked by the loader-specific entry points to log the platform banner; the real work
 * of reloading configs on lifecycle events happens in
 * {@link com.coolerpromc.coolerconfig.config.ConfigRegistry ConfigRegistry}.
 */
public final class CoolerConfig {

    private CoolerConfig() {}

    /** Called by the Fabric and NeoForge entry points during mod initialisation. */
    public static void init() {
        Constants.LOG.info("CoolerConfig initialized on {} ({})",
                Services.PLATFORM.getPlatformName(),
                Services.PLATFORM.getEnvironmentName());
    }
}
