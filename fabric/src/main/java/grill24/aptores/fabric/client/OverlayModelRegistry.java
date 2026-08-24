package grill24.aptores.fabric.client;

import grill24.aptores.OreTypeDefinition;
import net.minecraft.client.resources.model.BakedModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Captures the baked overlay (cube_all + ore-fragment cutout texture) models as they come
 * through {@code Context#modifyModelAfterBake()}, so {@link AptOresModelLoadingPlugin} can hand
 * them to the ore-model wrapper once baking finishes. All bakes complete before any rendering
 * happens, so it's safe for the wrapper to look these up lazily regardless of bake order.
 */
public final class OverlayModelRegistry {
    private static final Map<OreTypeDefinition, BakedModel> MODELS = new HashMap<>();

    private OverlayModelRegistry() {
    }

    static void reset() {
        MODELS.clear();
    }

    static void put(OreTypeDefinition type, BakedModel model) {
        MODELS.put(type, model);
    }

    public static BakedModel get(OreTypeDefinition type) {
        return MODELS.get(type);
    }
}
