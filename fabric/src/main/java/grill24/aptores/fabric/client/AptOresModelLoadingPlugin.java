package grill24.aptores.fabric.client;

import grill24.aptores.AptOres;
import grill24.aptores.AptOresConfig;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.fabric.client.model.AptOresBakedModel;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Wraps the vanilla baked models for every ore block Apt Ores knows about, purely at the
 * renderer level: no block, block entity, or worldgen is touched anywhere in this mod. This is
 * the same technique connected-texture mods (e.g. Continuity) use to pick a texture from
 * neighbor state at bake/mesh time, applied here to pick a whole backdrop model instead of a
 * single texture tile.
 *
 * <p>1.21.5 replaced the old {@code Context.addModels(List)} / {@code
 * Context.modifyModelAfterBake()} pair (keyed by the model's own {@code ResourceLocation}, fired
 * once per baked model - including our own overlay-only models, which is how the previous port's
 * {@code OverlayModelRegistry} learned about them) with a typed {@code ExtraModelKey<T>} /
 * {@code Context.addModel(key, UnbakedExtraModel)} mechanism (mirroring NeoForge's
 * {@code StandaloneModelKey} from the 1.21.4→1.21.5 NeoForge port - see
 * {@code neoforge/.../AptOresNeoForgeClient}) plus a real, block-state-keyed {@code
 * Context.modifyBlockModelAfterBake()} event whose {@code Context.state()} hands back the actual
 * {@link BlockState} directly - no more matching against a {@code ModelResourceLocation}/block-model
 * id by hand. {@code SimpleUnbakedExtraModel.blockStateModel(id)} bakes our overlay-only
 * (cube_all + cutout ore texture) model id as a full {@link BlockStateModel}, retrievable after
 * baking via {@code FabricBakedModelManager.getModel(key)} - see {@code AptOresBakedModel} for
 * why that lookup is deferred to first render rather than done here at bake time.
 */
public final class AptOresModelLoadingPlugin {
    private AptOresModelLoadingPlugin() {
    }

    public static void register() {
        ModelLoadingPlugin.register(context -> {
            AptOresConfig.load(FabricLoader.getInstance().getConfigDir());
            OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));

            // Pin our overlay-only models so they get loaded, baked, and stitched into the
            // block atlas even though no blockstate or item references them directly.
            Map<OreTypeDefinition, ExtraModelKey<BlockStateModel>> overlayKeys = new HashMap<>();
            for (OreTypeDefinition type : OreTypeRegistry.all()) {
                ExtraModelKey<BlockStateModel> key = ExtraModelKey.create(() -> "aptores overlay " + type.name());
                overlayKeys.put(type, key);
                context.addModel(key, SimpleUnbakedExtraModel.blockStateModel(type.overlayModelId()));
            }

            context.modifyBlockModelAfterBake().register((original, ctx) -> {
                BlockState state = ctx.state();
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                OreTypeDefinition type = OreTypeRegistry.byBlockId(blockId);
                if (type == null) {
                    return original;
                }

                ExtraModelKey<BlockStateModel> overlayKey = overlayKeys.get(type);
                if (overlayKey == null) {
                    return original;
                }

                return new AptOresBakedModel(type, original, overlayKey);
            });

            AptOres.LOGGER.info("Apt Ores: hooked model baking for {} ore types", OreTypeRegistry.all().size());
        });
    }
}
