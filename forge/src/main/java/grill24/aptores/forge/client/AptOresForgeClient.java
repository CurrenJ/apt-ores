package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.BlockModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * No blocks, items, or block entities are registered anywhere in this project, and nothing runs
 * on a dedicated server - this class is restricted to {@link Dist#CLIENT} so Forge never loads it
 * server-side. Ore rendering is achieved purely by post-processing the vanilla model bake result -
 * swapping each target ore's baked model for a composite that samples its neighbors live - the
 * same technique connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 *
 * <p>Unlike NeoForge, (Minecraft)Forge 1.21.4 no longer exposes a {@code ModelEvent.RegisterAdditional}
 * hook (or a {@code standaloneModels()} baking-result map) for pinning a model that no blockstate or
 * item references. To still get our overlay-only models loaded and baked, each one is shadowed by a
 * throwaway client item definition (see {@code assets/aptores/items/overlay_*.json}) - the vanilla
 * per-item model JSON loader indexes those by file path regardless of whether a real item exists
 * with that id, so they show up in {@link ModelBakery.BakingResult#itemStackModels()} without needing
 * any block/item registration. The resulting {@link ItemModel} is a plain {@link BlockModelWrapper}
 * for a "minecraft:model"-typed definition, whose public {@code model} field is the real baked model.
 */
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class AptOresForgeClient {

    /** The throwaway item-model id shadowing {@code type.overlayModelId()} (see class javadoc). */
    private static ResourceLocation overlayItemId(OreTypeDefinition type) {
        ResourceLocation modelId = type.overlayModelId();
        return ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), modelId.getPath().replaceFirst("^block/", ""));
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));

        ModelBakery.BakingResult bakingResult = event.getResults();
        Map<ModelResourceLocation, BakedModel> models = bakingResult.blockStateModels();
        Map<ResourceLocation, ItemModel> itemModels = bakingResult.itemStackModels();

        for (Map.Entry<ModelResourceLocation, BakedModel> entry : models.entrySet()) {
            ResourceLocation blockId = entry.getKey().id();
            OreTypeDefinition type = OreTypeRegistry.byBlockId(blockId);
            if (type == null) {
                continue;
            }

            ItemModel overlayItemModel = itemModels.get(overlayItemId(type));
            BakedModel overlayModel = overlayItemModel instanceof BlockModelWrapper wrapper ? wrapper.model : null;
            if (overlayModel == null) {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked; leaving {} untouched", type.name(), blockId);
                continue;
            }

            entry.setValue(new AptOresModel(type, entry.getValue(), overlayModel));
        }
    }
}
