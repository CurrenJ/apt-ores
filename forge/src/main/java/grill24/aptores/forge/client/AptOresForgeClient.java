package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * No blocks, items, or block entities are registered anywhere in this project, and nothing runs
 * on a dedicated server - {@link #init()} is only called on {@link Dist#CLIENT} (see below), so
 * none of this ever loads server-side. Ore rendering is achieved purely by post-processing the
 * vanilla model bake result -
 * swapping each target ore's baked model for a composite that samples its neighbors live - the
 * same technique connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 *
 * <p>Each overlay-only (cube_all + cutout ore texture) model is pinned via
 * {@link ModelEvent.RegisterModelStateDefinitions}, registered under a synthetic id backed by a
 * {@code assets/aptores/blockstates/overlay_*.json} file, so the overlay bakes through the normal
 * blockstate pipeline and shows up in
 * {@link net.minecraft.client.resources.model.ModelBakery.BakingResult#blockStateModels()} keyed
 * by a state we control.
 *
 * <p>{@link #newOverlayStateDefinition()} builds each {@link StateDefinition}/{@link BlockState}
 * pair directly via {@link StateDefinition}'s own public builder API - the same one vanilla's
 * {@link Block} constructor uses internally - rather than constructing a throwaway {@code Block}
 * to get one. A never-registered {@code Block} instance still unconditionally registers an
 * intrusive registry holder in its constructor, and that holder fails {@code
 * NamespacedWrapper.freeze()}'s "was every registry object registered?" check at startup ({@code
 * IllegalStateException: Some intrusive holders were not registered}) since the {@code Block} is
 * never actually registered. {@link Blocks#STONE} is only borrowed as the (never-invoked for a
 * property-less definition) generic owner reference; each call still produces its own distinct
 * {@link BlockState} object (default {@code Object} identity, since {@code BlockState} doesn't
 * override {@code equals}/{@code hashCode}), which is what lets {@link #onModifyBakingResult} key
 * {@link #OVERLAY_STATES} off it safely below.
 *
 * <p>This Forge version's event bus overhaul (eventbus 7.0-beta) stopped every {@link ModelEvent}
 * member from implementing {@code IModBusEvent}; each now has its own static {@code EventBus<T>}
 * instead. {@code @Mod.EventBusSubscriber} only handles {@code IModBusEvent}s, so both listeners
 * below are registered explicitly via {@link #init()}, called from
 * {@link grill24.aptores.forge.AptOresForge} on the client only.
 */
public class AptOresForgeClient {
    /** The synthetic per-type block state whose baked model is our pinned overlay geometry. */
    private static final Map<OreTypeDefinition, BlockState> OVERLAY_STATES = new HashMap<>();

    public static void init() {
        ModelEvent.RegisterModelStateDefinitions.BUS.addListener(AptOresForgeClient::onRegisterModelStateDefinitions);
        ModelEvent.ModifyBakingResult.BUS.addListener(AptOresForgeClient::onModifyBakingResult);
    }

    /** The throwaway block id shadowing {@code type.overlayModelId()} (see class javadoc). */
    private static Identifier overlayBlockId(OreTypeDefinition type) {
        Identifier modelId = type.overlayModelId();
        return Identifier.fromNamespaceAndPath(modelId.getNamespace(), modelId.getPath().replaceFirst("^block/", ""));
    }

    /** A fresh, single-state {@link StateDefinition} - see the class javadoc for why. */
    private static StateDefinition<Block, BlockState> newOverlayStateDefinition() {
        return new StateDefinition.Builder<Block, BlockState>(Blocks.STONE)
            .create(Block::defaultBlockState, BlockState::new);
    }

    private static void onRegisterModelStateDefinitions(ModelEvent.RegisterModelStateDefinitions event) {
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));
        OVERLAY_STATES.clear();

        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            StateDefinition<Block, BlockState> stateDefinition = newOverlayStateDefinition();
            event.register(overlayBlockId(type), stateDefinition);
            OVERLAY_STATES.put(type, stateDefinition.any());
        }
    }

    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<BlockState, BlockStateModel> models = event.getResults().blockStateModels();

        for (Map.Entry<BlockState, BlockStateModel> entry : models.entrySet()) {
            BlockState state = entry.getKey();
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
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
