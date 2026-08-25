package grill24.aptores.fabric.client;

import grill24.aptores.OreTypeDefinition;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricBakedModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns the {@link ExtraModelKey} each ore type's overlay (cube_all + ore-fragment cutout texture)
 * model is registered under (see {@link AptOresModelLoadingPlugin}), and resolves the baked
 * {@link BlockStateModel} for that key lazily once model baking finishes. All bakes complete
 * before any rendering happens, so it's safe for {@link grill24.aptores.fabric.client.model.AptOresBakedModel}
 * to look these up on demand regardless of registration order.
 */
public final class OverlayModelRegistry {
    private static final Map<OreTypeDefinition, ExtraModelKey<BlockStateModel>> KEYS = new HashMap<>();

    private OverlayModelRegistry() {
    }

    static void reset() {
        KEYS.clear();
    }

    static ExtraModelKey<BlockStateModel> keyFor(OreTypeDefinition type) {
        return KEYS.computeIfAbsent(type, t -> ExtraModelKey.create(t::name));
    }

    public static BlockStateModel get(OreTypeDefinition type) {
        ExtraModelKey<BlockStateModel> key = KEYS.get(type);
        if (key == null) {
            return null;
        }
        return ((FabricBakedModelManager) Minecraft.getInstance().getModelManager()).getModel(key);
    }
}
