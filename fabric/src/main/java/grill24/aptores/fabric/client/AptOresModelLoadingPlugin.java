package grill24.aptores.fabric.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.fabric.client.model.AptOresBlockStateModel;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wraps the baked block-state models for every ore block Apt Ores knows about, purely at the
 * renderer level: no block, block entity, or worldgen is touched anywhere in this mod. This is
 * the same technique connected-texture mods (e.g. Continuity) use to pick a texture from
 * neighbor state at bake/mesh time, applied here to pick a whole backdrop model instead of a
 * single texture tile.
 */
public final class AptOresModelLoadingPlugin {
    private AptOresModelLoadingPlugin() {
    }

    public static void register() {
        ModelLoadingPlugin.register(context -> {
            OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));
            OverlayModelRegistry.reset();

            // Pin our overlay-only (cube_all + cutout ore texture) models as extra models so they
            // get loaded, baked, and stitched into the block atlas even though no blockstate or
            // item references them directly. The baked BlockStateModel is resolved lazily at
            // render time via OverlayModelRegistry.
            for (OreTypeDefinition type : OreTypeRegistry.all()) {
                ExtraModelKey<BlockStateModel> key = ExtraModelKey.create();
                context.addModel(key, SimpleUnbakedExtraModel.blockStateModel(type.overlayModelId()));
                OverlayModelRegistry.put(type, key);
            }

            context.modifyBlockModelAfterBake().register((model, ctx) -> {
                BlockState state = ctx.state();
                OreTypeDefinition oreType = OreTypeRegistry.byBlockId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                return oreType != null ? new AptOresBlockStateModel(oreType, model) : model;
            });

            AptOres.LOGGER.info("Apt Ores: hooked model baking for {} ore types", OreTypeRegistry.all().size());
        });
    }
}
