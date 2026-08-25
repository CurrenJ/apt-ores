package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.registries.BuiltInRegistries;
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
import java.util.Map;

/**
 * No blocks, items, or block entities are registered anywhere in this project, and nothing runs
 * on a dedicated server - this class is restricted to {@link Dist#CLIENT} so Forge never loads it
 * server-side. Ore rendering is achieved purely by post-processing the vanilla model bake result -
 * swapping each target ore's block state model for a composite that samples its neighbors live -
 * the same technique connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 *
 * <p>Each overlay-only (cube_all + cutout ore texture) model is pinned via
 * {@link ModelEvent.RegisterModelStateDefinitions}, registered under a synthetic id backed by a
 * {@code assets/aptores/blockstates/overlay_*.json} file, so the overlay bakes through the normal
 * blockstate pipeline and shows up in {@link ModelBakery.BakingResult#blockStateModels()} keyed by
 * a state we control. The event wants a fresh {@link StateDefinition}/{@link BlockState} pair per
 * synthetic id; earlier versions of this class constructed a throwaway {@code Block} to get one,
 * but a never-registered {@code Block} instance still unconditionally registers an intrusive
 * registry holder in its constructor, and on this Forge version that holder fails {@code
 * NamespacedWrapper.freeze()}'s "was every registry object registered?" check at startup ({@code
 * IllegalStateException: Some intrusive holders were not registered}). {@link
 * #newOverlayStateDefinition()} instead builds the {@link StateDefinition} directly via its own
 * public builder API - the same one vanilla's {@code Block} constructor uses internally - so no
 * {@code Block} (and therefore no intrusive holder) is ever created; {@link Blocks#STONE} is only
 * borrowed as the (never-invoked for a property-less definition) generic owner reference. Each
 * call produces a distinct {@link BlockState} object (default {@code Object} identity, since
 * {@code BlockState} doesn't override {@code equals}/{@code hashCode}), which is what lets
 * {@link #onModifyBakingResult} key {@link #OVERLAY_STATES} off it safely below.
 */
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class AptOresForgeClient {
    /** The synthetic per-type state whose baked model is our pinned overlay geometry. */
    private static final Map<OreTypeDefinition, BlockState> OVERLAY_STATES = new HashMap<>();

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
        OVERLAY_STATES.clear();

        // Pin our overlay-only (cube_all + cutout ore texture) models so they get loaded, baked,
        // and stitched into the block atlas even though no real block/item references them.
        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            StateDefinition<Block, BlockState> stateDefinition = newOverlayStateDefinition();
            OVERLAY_STATES.put(type, stateDefinition.any());
            event.register(overlayBlockStateId(type), stateDefinition);
        }
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ModelBakery.BakingResult bakingResult = event.getResults();
        Map<BlockState, BlockStateModel> models = bakingResult.blockStateModels();

        for (Map.Entry<BlockState, BlockStateModel> entry : models.entrySet()) {
            BlockState state = entry.getKey();
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            OreTypeDefinition type = OreTypeRegistry.byBlockId(blockId);
            if (type == null) {
                continue;
            }

            BlockState overlayState = OVERLAY_STATES.get(type);
            BlockStateModel overlayModel = overlayState == null ? null : models.get(overlayState);
            if (overlayModel == null) {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked; leaving {} untouched", type.name(), blockId);
                continue;
            }

            entry.setValue(new AptOresModel(type, entry.getValue(), overlayModel));
        }
    }
}
