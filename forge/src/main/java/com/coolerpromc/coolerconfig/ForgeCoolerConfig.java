package com.coolerpromc.coolerconfig;

import com.coolerpromc.coolerconfig.config.ConfigRegistry;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class ForgeCoolerConfig {
    public ForgeCoolerConfig() {
        CoolerConfig.init();
        ServerStartingEvent.BUS.addListener(ForgeCoolerConfig::onServerStarting);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        ConfigRegistry.reloadForServer();
    }
}