package com.coolerpromc.coolerconfig;

import com.coolerpromc.coolerconfig.platform.Services;

public class CoolerConfig {

    public static void init() {
        Constants.LOG.info("CoolerConfig initialized on {} ({})",
                Services.PLATFORM.getPlatformName(),
                Services.PLATFORM.getEnvironmentName());
    }
}
