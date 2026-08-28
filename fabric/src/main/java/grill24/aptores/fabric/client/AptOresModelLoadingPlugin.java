package grill24.aptores.fabric.client;

import grill24.aptores.AptOres;
import grill24.aptores.AptOresConfig;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.fabric.client.model.AptOresBakedModel;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

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
            AptOresConfig.load(FabricLoader.getInstance().getConfigDir());
            OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));
            OverlayModelRegistry.reset();

            // Pin our overlay-only models so they get loaded, baked, and stitched into the
            // block atlas even though no blockstate or item references them directly.
            List<ResourceLocation> overlayModelIds = new ArrayList<>();
            for (OreTypeDefinition type : OreTypeRegistry.all()) {
                overlayModelIds.add(type.overlayModelId());
            }
            context.addModels(overlayModelIds);

            context.modifyModelAfterBake().register((model, ctx) -> {
                ResourceLocation resourceId = ctx.resourceId();

                for (OreTypeDefinition type : OreTypeRegistry.all()) {
                    if (type.overlayModelId().equals(resourceId)) {
                        OverlayModelRegistry.put(type, model);
                        return model;
                    }
                }

                OreTypeDefinition oreType = OreTypeRegistry.byBlockModelId(resourceId);
                if (oreType != null) {
                    return new AptOresBakedModel(oreType, model);
                }

                return model;
            });

            AptOres.LOGGER.info("Apt Ores: hooked model baking for {} ore types", OreTypeRegistry.all().size());
        });
    }
}
