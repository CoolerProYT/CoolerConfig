package com.coolerpromc.coolerconfig;

import com.coolerpromc.coolerconfig.config.ConfigRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class FabricCoolerConfig implements ModInitializer {

    @Override
    public void onInitialize() {
        CoolerConfig.init();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> ConfigRegistry.reloadForServer());
    }
}
