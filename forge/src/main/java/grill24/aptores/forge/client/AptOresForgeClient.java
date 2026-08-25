package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * No blocks, items, or block entities are registered anywhere in this project, and nothing runs
 * on a dedicated server - this class is restricted to {@link Dist#CLIENT} so Forge never loads it
 * server-side. Ore rendering is achieved purely by post-processing the vanilla model bake result -
 * swapping each target ore's baked model for a composite that samples its neighbors live - the
 * same technique connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 *
 * <p>Each overlay-only (cube_all + cutout ore texture) model is pinned via
 * {@link ModelEvent.RegisterModelStateDefinitions}, registered under a synthetic id backed by a
 * {@code assets/aptores/blockstates/overlay_*.json} file, so the overlay bakes through the normal
 * blockstate pipeline and shows up in {@link ModelBakery.BakingResult#blockStateModels()}. The
 * event wants a fresh {@link StateDefinition}/{@link BlockState} pair per synthetic id; earlier
 * versions of this class constructed a throwaway {@code Block} to get one, but a never-registered
 * {@code Block} instance still unconditionally registers an intrusive registry holder in its
 * constructor, and on this Forge version that holder fails {@code NamespacedWrapper.freeze()}'s
 * "was every registry object registered?" check at startup ({@code IllegalStateException: Some
 * intrusive holders were not registered}). {@link #newOverlayStateDefinition()} instead builds
 * the {@link StateDefinition} directly via its own public builder API - the same one vanilla's
 * {@code Block} constructor uses internally - so no {@code Block} (and therefore no intrusive
 * holder) is ever created; {@link Blocks#STONE} is only borrowed as the (never-invoked for a
 * property-less definition) generic owner reference.
 */
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class AptOresForgeClient {
    /** The throwaway block id shadowing {@code type.overlayModelId()} (see class javadoc). */
    private static ResourceLocation overlayBlockStateId(OreTypeDefinition type) {
        ResourceLocation modelId = type.overlayModelId();
        return ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), modelId.getPath().replaceFirst("^block/", ""));
    }

    /** A fresh, single-state {@link StateDefinition} - see the class javadoc for why. */
    private static StateDefinition<Block, BlockState> newOverlayStateDefinition() {
        return new StateDefinition.Builder<Block, BlockState>(Blocks.STONE)
            .create(Block::defaultBlockState, BlockState::new);
    }

    @SubscribeEvent
    public static void onRegisterModelStateDefinitions(ModelEvent.RegisterModelStateDefinitions event) {
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));

        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            event.register(overlayBlockStateId(type), newOverlayStateDefinition());
        }
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ModelBakery.BakingResult bakingResult = event.getResults();
        Map<ModelResourceLocation, BakedModel> models = bakingResult.blockStateModels();

        // Index the pinned overlay models by their synthetic block id (the MRL id) so we can look
        // them up regardless of the exact variant suffix the blockstate loader assigns.
        Set<ResourceLocation> syntheticIds = new HashSet<>();
        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            syntheticIds.add(overlayBlockStateId(type));
        }

        Map<ResourceLocation, BakedModel> overlayModels = new HashMap<>();
        for (Map.Entry<ModelResourceLocation, BakedModel> entry : models.entrySet()) {
            ResourceLocation id = entry.getKey().id();
            if (syntheticIds.contains(id)) {
                overlayModels.put(id, entry.getValue());
            }
        }

        for (Map.Entry<ModelResourceLocation, BakedModel> entry : models.entrySet()) {
            ResourceLocation blockId = entry.getKey().id();
            OreTypeDefinition type = OreTypeRegistry.byBlockId(blockId);
            if (type == null) {
                continue;
            }

            BakedModel overlayModel = overlayModels.get(overlayBlockStateId(type));
            if (overlayModel == null) {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked; leaving {} untouched", type.name(), blockId);
                continue;
            }

            entry.setValue(new AptOresModel(type, entry.getValue(), overlayModel));
        }
    }
}
