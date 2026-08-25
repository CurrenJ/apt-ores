package grill24.aptores.fabric.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.fabric.client.model.AptOresBakedModel;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wraps the vanilla baked models for every ore block Apt Ores knows about, purely at the
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

            // Pin our overlay-only models so they get loaded, baked, and stitched into the
            // block atlas even though no blockstate or item references them directly.
            for (OreTypeDefinition type : OreTypeRegistry.all()) {
                context.addModel(OverlayModelRegistry.keyFor(type), SimpleUnbakedExtraModel.blockStateModel(type.overlayModelId()));
            }

            context.modifyBlockModelAfterBake().register((model, ctx) -> {
                BlockState state = ctx.state();
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                OreTypeDefinition oreType = OreTypeRegistry.byBlockId(blockId);
                if (oreType == null) {
                    return model;
                }

                return new AptOresBakedModel(oreType, model);
            });

            AptOres.LOGGER.info("Apt Ores: hooked model baking for {} ore types", OreTypeRegistry.all().size());
        });
    }
}
