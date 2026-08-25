package grill24.aptores.neoforge;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.neoforge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;

import java.util.HashMap;
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
    /** Keys the overlay-only geometry was pinned under via {@link ModelEvent.RegisterStandalone}. */
    private static final Map<OreTypeDefinition, StandaloneModelKey<QuadCollection>> OVERLAY_KEYS = new HashMap<>();

    @SubscribeEvent
    public static void onRegisterStandalone(ModelEvent.RegisterStandalone event) {
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));
        OVERLAY_KEYS.clear();

        // Pin our overlay-only (cube_all + cutout ore texture) models as standalone models so
        // they get loaded, baked, and stitched into the block atlas even though no
        // blockstate/item references them.
        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            ResourceLocation modelId = type.overlayModelId();
            StandaloneModelKey<QuadCollection> key = new StandaloneModelKey<>(modelId::toString);
            OVERLAY_KEYS.put(type, key);
            event.register(key, SimpleUnbakedStandaloneModel.quadCollection(modelId));
        }
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ModelBakery.BakingResult bakingResult = event.getBakingResult();
        Map<BlockState, BlockStateModel> models = bakingResult.blockStateModels();

        for (Map.Entry<BlockState, BlockStateModel> entry : models.entrySet()) {
            BlockState state = entry.getKey();
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            OreTypeDefinition type = OreTypeRegistry.byBlockId(blockId);
            if (type == null) {
                continue;
            }

            StandaloneModelKey<QuadCollection> key = OVERLAY_KEYS.get(type);
            QuadCollection overlayQuads = key == null ? null : bakingResult.standaloneModels().get(key);
            if (overlayQuads == null) {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked; leaving {} untouched", type.name(), blockId);
                continue;
            }

            entry.setValue(new AptOresModel(type, entry.getValue(), overlayQuads));
        }
    }
}
