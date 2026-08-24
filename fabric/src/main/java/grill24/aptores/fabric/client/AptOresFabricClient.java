package grill24.aptores.fabric.client;

import grill24.aptores.AptOres;
import net.fabricmc.api.ClientModInitializer;

public final class AptOresFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AptOres.LOGGER.info("Initializing Apt Ores");
        AptOresModelLoadingPlugin.register();
    }
}
