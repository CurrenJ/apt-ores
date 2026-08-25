package grill24.aptores.fabric.client;

import grill24.aptores.OreTypeDefinition;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds the {@link ExtraModelKey} for each ore type's overlay-only (cube_all + cutout texture)
 * model, registered from {@link AptOresModelLoadingPlugin} every client resource reload. The
 * overlay model itself isn't captured at bake time - the key is resolved through the mixed-in
 * {@link FabricModelManager} at render time, by which point every extra model is fully baked.
 */
public final class OverlayModelRegistry {
    private static final Map<OreTypeDefinition, ExtraModelKey<BlockStateModel>> KEYS = new HashMap<>();

    private OverlayModelRegistry() {
    }

    static void reset() {
        KEYS.clear();
    }

    static void put(OreTypeDefinition type, ExtraModelKey<BlockStateModel> key) {
        KEYS.put(type, key);
    }

    public static BlockStateModel get(OreTypeDefinition type) {
        ExtraModelKey<BlockStateModel> key = KEYS.get(type);
        if (key == null) {
            return null;
        }
        return ((FabricModelManager) Minecraft.getInstance().getModelManager()).getModel(key);
    }
}
