package grill24.aptores.fabric.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.fabric.client.model.AptOresBakedModel;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

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

            // Pin our overlay-only (cube_all + cutout ore texture) models as "extra" models so
            // they get loaded, baked, and stitched into the block atlas even though no
            // blockstate/item references them; the baked result is fetched lazily later via the
            // key stashed in OverlayModelRegistry.
            for (OreTypeDefinition type : OreTypeRegistry.all()) {
                ResourceLocation modelId = type.overlayModelId();
                ExtraModelKey<BlockStateModel> key = ExtraModelKey.create(modelId::toString);
                OverlayModelRegistry.put(type, key);
                context.addModel(key, SimpleUnbakedExtraModel.blockStateModel(modelId));
            }

            context.modifyBlockModelAfterBake().register((model, ctx) -> {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(ctx.state().getBlock());
                OreTypeDefinition oreType = OreTypeRegistry.byBlockId(blockId);
                if (oreType != null) {
                    return new AptOresBakedModel(oreType, model);
                }
                return model;
            });

            AptOres.LOGGER.info("Apt Ores: hooked model baking for {} ore types", OreTypeRegistry.all().size());
        });
    }
}
