package com.coolerpromc.coolerconfig;

import com.coolerpromc.coolerconfig.config.ConfigRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

public class NeoForgeCoolerConfigClient {
    public static void initClient(IEventBus modBus) {
        modBus.addListener(NeoForgeCoolerConfigClient::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ConfigRegistry::reloadForClient);
    }
}