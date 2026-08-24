package grill24.aptores.forge;

import grill24.aptores.AptOres;
import net.minecraftforge.fml.common.Mod;

/**
 * Mod entry point required by Forge. All rendering logic lives in
 * {@link grill24.aptores.forge.client.AptOresForgeClient}, which Forge only loads on the client -
 * unlike NeoForge, Forge's {@code @Mod} annotation has no {@code dist} restriction, so the
 * client-only event handlers are kept in a separate {@code @Mod.EventBusSubscriber(value =
 * Dist.CLIENT)} class instead of here.
 */
@Mod(AptOres.MOD_ID)
public class AptOresForge {
    public AptOresForge() {
    }
}
