package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import grill24.aptores.forge.client.model.BakedQuadBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * No blocks, items, or block entities are registered anywhere in this project, and nothing runs
 * on a dedicated server - this class is restricted to {@link Dist#CLIENT} so Forge never loads it
 * server-side. Ore rendering is achieved purely by post-processing the vanilla model bake result -
 * swapping each target ore's baked model for a composite that samples its neighbors live - the
 * same technique connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 *
 * <p>Forge 26.1 has no hook for pinning a model that no blockstate or item references, so each
 * overlay-only model is shadowed by a throwaway client item definition (see
 * {@code assets/aptores/items/overlay_*.json}) - the vanilla per-item model JSON loader indexes
 * those by file path regardless of whether a real item exists with that id, so they show up in
 * {@link ModelBakery.BakingResult#itemStackModels()} without needing any block/item registration.
 * The resulting {@link ItemModel} is a {@link BlockStateModelWrapper} for a "minecraft:model"-typed
 * definition, whose {@code model} field (made public by our access transformer) is the real baked
 * {@link BlockStateModel}.
 */
// Forge 26.1 moved to per-event buses (eventbus 7): ModelEvent.ModifyBakingResult is no longer an
// IModBusEvent and carries its own static BUS (created in the DEFAULT group). Registering on the
// MOD bus is rejected ("BusGroup ... requires IModBusEvent"). Bus.BOTH routes each @SubscribeEvent
// method automatically - IModBusEvent -> the mod bus group, everything else -> the event's own
// per-event bus in the DEFAULT group - so this handler lands on ModelEvent.ModifyBakingResult.BUS.
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.BOTH, value = Dist.CLIENT)
public class AptOresForgeClient {

    /** The throwaway item-model id shadowing {@code type.overlayModelId()} (see class javadoc). */
    private static Identifier overlayItemId(OreTypeDefinition type) {
        Identifier modelId = type.overlayModelId();
        return Identifier.fromNamespaceAndPath(modelId.getNamespace(), modelId.getPath().replaceFirst("^block/", ""));
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));
        OverlayModelRegistry.reset();

        // Unwrap the shadowed overlay item models into their baked BlockStateModels before
        // wrapping anything, so a missing overlay doesn't corrupt the ores that do exist.
        ModelBakery.BakingResult bakingResult = event.getResults();
        Map<Identifier, ItemModel> itemModels = bakingResult.itemStackModels();
        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            ItemModel overlayItemModel = itemModels.get(overlayItemId(type));
            BlockStateModel overlayModel = null;
            if (overlayItemModel instanceof BlockStateModelWrapper wrapper) {
                overlayModel = wrapper.model;
            } else if (overlayItemModel instanceof CuboidItemModelWrapper cuboid) {
                // 26.1 bakes a "minecraft:model" item def pointing at a block model into a cuboid
                // (pre-baked quad collection) rather than a BlockStateModelWrapper; unwrap the
                // quads and re-present them as the overlay BlockStateModel.
                overlayModel = new BakedQuadBlockStateModel(cuboid.quads);
            }
            if (overlayModel != null) {
                OverlayModelRegistry.put(type, overlayModel);
            } else {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked as a usable item model (got {}); leaving it untouched",
                    type.name(), overlayItemModel == null ? "null" : overlayItemModel.getClass().getSimpleName());
            }
        }

        // The baking result's block-state map is mutable; swap each target ore's model for a
        // composite that samples its neighbors live.
        Map<BlockState, BlockStateModel> models = bakingResult.blockStateModels();
        for (Map.Entry<BlockState, BlockStateModel> entry : models.entrySet()) {
            OreTypeDefinition type = OreTypeRegistry.byBlockId(BuiltInRegistries.BLOCK.getKey(entry.getKey().getBlock()));
            if (type != null) {
                entry.setValue(new AptOresModel(type, entry.getValue()));
            }
        }
    }
}
