package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
 * <p>Regular (Minecraft)Forge 1.21.9 still has no {@code ModelEvent.RegisterAdditional}-style hook
 * (or a {@code standaloneModels()} baking-result facility like NeoForge's) for pinning a model
 * that no blockstate or item references. Instead, each overlay model is shadowed by a synthetic
 * {@link StateDefinition} registered via {@code ModelEvent.RegisterModelStateDefinitions} - the
 * vanilla blockstate-model loader then bakes {@code assets/aptores/blockstates/overlay_*.json} for
 * it exactly like a real block, and the result shows up in
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
 */
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class AptOresForgeClient {
    /** The synthetic per-type block state whose baked model is our pinned overlay geometry. */
    private static final Map<OreTypeDefinition, BlockState> OVERLAY_STATES = new HashMap<>();

    /** The throwaway block id shadowing {@code type.overlayModelId()} (see class javadoc). */
    private static ResourceLocation overlayBlockId(OreTypeDefinition type) {
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

        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            StateDefinition<Block, BlockState> stateDefinition = newOverlayStateDefinition();
            event.register(overlayBlockId(type), stateDefinition);
            OVERLAY_STATES.put(type, stateDefinition.any());
        }
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<BlockState, BlockStateModel> models = event.getResults().blockStateModels();

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
