package grill24.aptores.forge.client;

import grill24.aptores.OreTypeDefinition;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds the baked overlay-only (cube_all + cutout ore texture) {@link BlockStateModel} for each
 * ore type, extracted at {@link net.minecraftforge.client.event.ModelEvent.ModifyBakingResult}
 * time from the item-shadow models (see {@link AptOresForgeClient}). The render hot path does a
 * plain map lookup.
 */
public final class OverlayModelRegistry {
    private static final Map<OreTypeDefinition, BlockStateModel> BAKED = new HashMap<>();

    private OverlayModelRegistry() {
    }

    public static void reset() {
        BAKED.clear();
    }

    public static void put(OreTypeDefinition type, BlockStateModel model) {
        BAKED.put(type, model);
    }

    public static BlockStateModel get(OreTypeDefinition type) {
        return BAKED.get(type);
    }
}
