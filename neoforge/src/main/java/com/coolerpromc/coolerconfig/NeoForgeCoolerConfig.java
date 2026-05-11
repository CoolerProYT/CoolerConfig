package com.coolerpromc.coolerconfig;

import com.coolerpromc.coolerconfig.config.ConfigRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

@Mod(Constants.MOD_ID)
public class NeoForgeCoolerConfig {
    public NeoForgeCoolerConfig(IEventBus modEventBus) {
    }

    public static void init() {
        CoolerConfig.init();
        NeoForge.EVENT_BUS.addListener(NeoForgeCoolerConfig::onServerStarting);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        ConfigRegistry.reloadForServer();
    }
}
