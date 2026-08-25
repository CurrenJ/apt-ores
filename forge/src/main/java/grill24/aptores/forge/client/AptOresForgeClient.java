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
 * <p>Unlike NeoForge (which added a {@code ModelEvent.RegisterStandalone} hook plus a
 * {@code standaloneModels()} baking-result map specifically for pinning a model that no
 * blockstate or item references), regular (Minecraft)Forge still has neither as of 1.21.6, and
 * the 1.21.4-era workaround of shadowing each overlay with a throwaway per-item model JSON no
 * longer works either - {@code BlockModelWrapper} (the baked {@link net.minecraft.client.renderer.item.ItemModel}
 * for a plain block-model item definition) now stores its quads in a private field with no public
 * accessor, so there's nothing left to unwrap.
 *
 * <p>Instead, each overlay is pinned via {@link ModelEvent.RegisterModelStateDefinitions} -
 * an event added in 1.21.6 specifically to let a mod register a {@link StateDefinition} for a
 * "synthetic block" (one with no real {@link Block} registration) so its models still get
 * discovered and baked from a normal {@code assets/<namespace>/blockstates/<path>.json} file, the
 * same way a real block's models are. Each of the 8 built-in ore types ships one such blockstate
 * JSON (see {@code assets/aptores/blockstates/overlay_*.json}), each with a single no-property
 * variant pointing at the ore's existing overlay block model.
 *
 * <p>{@link #newOverlayStateDefinition()} builds each {@link StateDefinition}/{@link BlockState}
 * pair directly via {@link StateDefinition}'s own public builder API - the same one vanilla's
 * {@link Block} constructor uses internally - rather than constructing a throwaway {@code Block}
 * to get one. A never-registered {@code Block} instance still unconditionally registers an
 * intrusive registry holder in its constructor (regardless of whether {@code Properties.of()} is
 * given an id), and that holder fails {@code NamespacedWrapper.freeze()}'s "was every registry
 * object registered?" check at startup ({@code IllegalStateException: Some intrusive holders were
 * not registered}) since the {@code Block} is never actually registered. {@link Blocks#STONE} is
 * only borrowed as the (never-invoked for a property-less definition) generic owner reference;
 * each call still produces its own distinct {@link BlockState} object (default {@code Object}
 * identity, since {@code BlockState} doesn't override {@code equals}/{@code hashCode}), which is
 * what lets {@link #onModifyBakingResult} key {@link #OVERLAY_STATES} off it safely below.
 *
 * <p>Note this means third-party ore types added purely via an {@code ore_types} JSON (see
 * {@code OreTypeLoader}) - the plug-in mechanism the other two loaders support with zero Java
 * code - do not get their overlay baked on regular Forge unless they also ship a matching
 * blockstate JSON; this mirrors the same pre-existing limitation the 1.21.4-era item-JSON
 * workaround had on this loader.
 */
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class AptOresForgeClient {

    /** The synthetic per-type state whose baked model is our pinned overlay geometry. */
    private static final Map<OreTypeDefinition, BlockState> OVERLAY_STATES = new HashMap<>();

    /** The synthetic blockstate id shadowing {@code type.overlayModelId()} (see class javadoc). */
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
