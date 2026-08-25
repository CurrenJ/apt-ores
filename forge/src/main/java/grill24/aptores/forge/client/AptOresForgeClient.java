package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * No blocks, items, or block entities are registered anywhere in this project, and nothing runs
 * on a dedicated server - this class is restricted to {@link Dist#CLIENT} so Forge never loads it
 * server-side. Ore rendering is achieved purely by post-processing the vanilla model bake result -
 * swapping each target ore's baked model for a composite that samples its neighbors live - the
 * same technique connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 *
 * <p>Each overlay-only (cube_all + cutout ore texture) model is pinned via
 * {@link ModelEvent.RegisterModelStateDefinitions}: a property-less {@link StateDefinition} is
 * built purely to obtain a throwaway {@link BlockState}, registered under a synthetic id backed by
 * an {@code assets/aptores/blockstates/overlay_*.json} file, so the overlay bakes through the
 * normal blockstate pipeline and shows up in
 * {@link net.minecraft.client.resources.model.ModelBakery.BakingResult#blockStateModels()} keyed by
 * that throwaway state.
 */
// Forge 26.1 moved to per-event buses (eventbus 7): ModelEvent.ModifyBakingResult and
// ModelEvent.RegisterModelStateDefinitions are no longer IModBusEvents and carry their own static
// BUS (created in the DEFAULT group). Bus.BOTH routes each @SubscribeEvent method automatically -
// IModBusEvent -> the mod bus group, everything else -> the event's own per-event bus - so both
// handlers land on their event's own BUS.
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.BOTH, value = Dist.CLIENT)
public class AptOresForgeClient {
    /** The synthetic per-type block state whose baked model is our pinned overlay geometry. */
    private static final Map<OreTypeDefinition, BlockState> OVERLAY_STATES = new HashMap<>();

    /** The throwaway block id shadowing {@code type.overlayModelId()} (see class javadoc). */
    private static Identifier overlayBlockId(OreTypeDefinition type) {
        Identifier modelId = type.overlayModelId();
        return Identifier.fromNamespaceAndPath(modelId.getNamespace(), modelId.getPath().replaceFirst("^block/", ""));
    }

    @SubscribeEvent
    public static void onRegisterModelStateDefinitions(ModelEvent.RegisterModelStateDefinitions event) {
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));
        OVERLAY_STATES.clear();

        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            StateDefinition<Block, BlockState> stateDefinition = syntheticStateDefinition();
            event.register(overlayBlockId(type), stateDefinition);
            OVERLAY_STATES.put(type, stateDefinition.any());
        }
    }

    /**
     * A property-less {@link StateDefinition} yielding exactly one throwaway {@link BlockState},
     * distinct (by identity, which is how baked-model maps are keyed) from every registered
     * block's states.
     *
     * <p>Deliberately not backed by a {@code new Block(...)}: constructing a Block allocates an
     * intrusive holder in the block registry, and one that never gets registered makes
     * {@code NamespacedWrapper.freeze} throw ("Some intrusive holders were not registered") the
     * moment the client builds its registry access. Reusing {@link Blocks#STONE} as the
     * definition's owner sidesteps that - the owner is only consulted for behaviour flags this
     * state never exercises, since it exists purely as a baking key.
     */
    private static StateDefinition<Block, BlockState> syntheticStateDefinition() {
        // Self-reference so the definition's properties codec decodes to this definition's own
        // state rather than to real stone; StateDefinition only calls the function lazily.
        StateDefinition<Block, BlockState>[] self = newDefinitionRef();
        self[0] = new StateDefinition.Builder<Block, BlockState>(Blocks.STONE)
            .create(owner -> self[0].any(), BlockState::new);
        return self[0];
    }

    @SuppressWarnings("unchecked")
    private static StateDefinition<Block, BlockState>[] newDefinitionRef() {
        return new StateDefinition[1];
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
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

            entry.setValue(new AptOresModel(type, state, entry.getValue(), overlayModel));
        }
    }
}
