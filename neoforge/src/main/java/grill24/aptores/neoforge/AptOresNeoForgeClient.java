package grill24.aptores.neoforge;

import grill24.aptores.AptOres;
import grill24.aptores.AptOresConfig;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.neoforge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ModelEvent;

import java.util.Map;

/**
 * The entire mod: no blocks, items, or block entities are registered anywhere in this project,
 * and nothing runs on a dedicated server (this class only loads on {@link Dist#CLIENT}). Ore
 * rendering is achieved purely by post-processing the vanilla model bake result - swapping each
 * target ore's baked model for a composite that samples its neighbors live - the same technique
 * connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 */
@EventBusSubscriber(modid = AptOres.MOD_ID, value = Dist.CLIENT)
@Mod(value = AptOres.MOD_ID, dist = Dist.CLIENT)
public class AptOresNeoForgeClient implements IModBusEvent {

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        AptOresConfig.load(FMLPaths.CONFIGDIR.get());
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));

        // Pin our overlay-only (cube_all + cutout ore texture) models so they get loaded,
        // baked, and stitched into the block atlas even though no blockstate references them.
        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            event.register(type.overlayModelId());
        }
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ModelBakery.BakingResult bakingResult = event.getBakingResult();
        Map<ModelResourceLocation, BakedModel> models = bakingResult.blockStateModels();
        Map<ResourceLocation, BakedModel> standaloneModels = bakingResult.standaloneModels();

        for (Map.Entry<ModelResourceLocation, BakedModel> entry : models.entrySet()) {
            ResourceLocation blockId = entry.getKey().id();
            OreTypeDefinition type = OreTypeRegistry.byBlockId(blockId);
            if (type == null) {
                continue;
            }

            BakedModel overlayModel = standaloneModels.get(type.overlayModelId());
            if (overlayModel == null) {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked; leaving {} untouched", type.name(), blockId);
                continue;
            }

            entry.setValue(new AptOresModel(type, entry.getValue(), overlayModel));
        }
    }
}
