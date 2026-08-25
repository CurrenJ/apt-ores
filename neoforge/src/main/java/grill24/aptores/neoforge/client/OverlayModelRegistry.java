package grill24.aptores.neoforge.client;

import grill24.aptores.OreTypeDefinition;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds the {@link StandaloneModelKey} for each ore type's overlay-only (cube_all + cutout
 * texture) model, registered from {@link AptOresNeoForgeClient#onRegisterStandalone} every
 * client resource reload. The baked overlay is resolved through the baking result's
 * {@code standaloneModels()} map at {@link net.neoforged.neoforge.client.event.ModelEvent.BakingCompleted}
 * time and cached here for the render hot path.
 */
public final class OverlayModelRegistry {
    private static final Map<OreTypeDefinition, StandaloneModelKey<BlockStateModel>> KEYS = new HashMap<>();
    private static final Map<OreTypeDefinition, BlockStateModel> BAKED = new HashMap<>();

    private OverlayModelRegistry() {
    }

    public static void reset() {
        KEYS.clear();
        BAKED.clear();
    }

    public static void putKey(OreTypeDefinition type, StandaloneModelKey<BlockStateModel> key) {
        KEYS.put(type, key);
    }

    public static void putBaked(OreTypeDefinition type, BlockStateModel model) {
        BAKED.put(type, model);
    }

    public static StandaloneModelKey<BlockStateModel> getKey(OreTypeDefinition type) {
        return KEYS.get(type);
    }

    public static BlockStateModel get(OreTypeDefinition type) {
        return BAKED.get(type);
    }
}
