package grill24.aptores.fabric.client;

import grill24.aptores.OreTypeDefinition;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.minecraft.client.renderer.block.model.BlockStateModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the {@link ExtraModelKey} each overlay (cube_all + ore-fragment cutout texture) model was
 * pinned under via {@code Context#addModel}, so {@link AptOresModelLoadingPlugin} - and later the
 * composite model itself - can fetch the actual baked {@link BlockStateModel} lazily through
 * {@code FabricBakedModelManager#getModel(ExtraModelKey)} once baking finishes. Extra models are
 * guaranteed to be baked before anything renders, so a lazy lookup here is always safe regardless
 * of bake order.
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

    public static ExtraModelKey<BlockStateModel> key(OreTypeDefinition type) {
        return KEYS.get(type);
    }
}
