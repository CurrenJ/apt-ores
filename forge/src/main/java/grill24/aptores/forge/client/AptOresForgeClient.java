package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.AptOresConfig;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.Map;

/**
 * No blocks, items, or block entities are registered anywhere in this project, and nothing runs
 * on a dedicated server - this class is restricted to {@link Dist#CLIENT} so Forge never loads it
 * server-side. Ore rendering is achieved purely by post-processing the vanilla model bake result -
 * swapping each target ore's baked model for a composite that samples its neighbors live - the
 * same technique connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 */
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class AptOresForgeClient {

    // Regular Forge's ModelResourceLocation (unlike NeoForge's patched copy) has no standalone()
    // factory - reproduce it directly via the constructor + the same "standalone" variant string.
    private static ModelResourceLocation standalone(ResourceLocation id) {
        return new ModelResourceLocation(id, "standalone");
    }

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        AptOresConfig.load(FMLPaths.CONFIGDIR.get());
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));

        // Pin our overlay-only (cube_all + cutout ore texture) models so they get loaded,
        // baked, and stitched into the block atlas even though no blockstate references them.
        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            event.register(standalone(type.overlayModelId()));
        }
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();

        for (Map.Entry<ModelResourceLocation, BakedModel> entry : models.entrySet()) {
            // Only intercept blockstate models, never the inventory (block item) model - the
            // background swap is a purely in-world, situational effect and the item icon should
            // keep showing its own baked (e.g. deepslate) texture untouched.
            if (entry.getKey().variant().equals(ModelResourceLocation.INVENTORY_VARIANT)) {
                continue;
            }

            ResourceLocation blockId = entry.getKey().id();
            OreTypeDefinition type = OreTypeRegistry.byBlockId(blockId);
            if (type == null) {
                continue;
            }

            BakedModel overlayModel = models.get(standalone(type.overlayModelId()));
            if (overlayModel == null) {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked; leaving {} untouched", type.name(), blockId);
                continue;
            }

            entry.setValue(new AptOresModel(type, entry.getValue(), overlayModel));
        }
    }
}
