package grill24.aptores.forge.client;

import grill24.aptores.OreTypeDefinition;
import net.minecraft.client.renderer.block.model.BlockStateModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds each ore type's baked overlay (cube_all + cutout ore texture) {@link BlockStateModel},
 * rebuilt from the throwaway item-model shadow (see {@link AptOresForgeClient}) every client
 * resource reload.
 */
public final class OverlayModelRegistry {
    private static final Map<OreTypeDefinition, BlockStateModel> BAKED = new HashMap<>();

    private OverlayModelRegistry() {
    }

    static void reset() {
        BAKED.clear();
    }

    static void put(OreTypeDefinition type, BlockStateModel model) {
        BAKED.put(type, model);
    }

    public static BlockStateModel get(OreTypeDefinition type) {
        return BAKED.get(type);
    }
}
