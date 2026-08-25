package grill24.aptores.forge;

import grill24.aptores.AptOres;
import grill24.aptores.forge.client.AptOresForgeClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Mod entry point required by Forge. All rendering logic lives in
 * {@link grill24.aptores.forge.client.AptOresForgeClient}; unlike NeoForge, Forge's {@code @Mod}
 * annotation has no {@code dist} restriction, so this constructor explicitly checks
 * {@link FMLEnvironment#dist} before touching any client-only class.
 */
@Mod(AptOres.MOD_ID)
public class AptOresForge {
    public AptOresForge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            AptOresForgeClient.init();
        }
    }
}
