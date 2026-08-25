package grill24.aptores.neoforge;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.neoforge.client.OverlayModelRegistry;
import grill24.aptores.neoforge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelLoader;

import java.util.Map;

/**
 * The entire mod: no blocks, items, or block entities are registered anywhere in this project,
 * and nothing runs on a dedicated server (this class only loads on {@link Dist#CLIENT}). Ore
 * rendering is achieved purely by post-processing the vanilla model bake result - swapping each
 * target ore's baked model for a composite that samples its neighbors live - the same technique
 * connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 */
@Mod(value = AptOres.MOD_ID, dist = Dist.CLIENT)
public class AptOresNeoForgeClient {
    public AptOresNeoForgeClient(IEventBus modEventBus) {
        modEventBus.addListener(this::onRegisterStandalone);
        modEventBus.addListener(this::onModifyBakingResult);
        modEventBus.addListener(this::onBakingCompleted);
    }

    private void onRegisterStandalone(ModelEvent.RegisterStandalone event) {
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));
        OverlayModelRegistry.reset();

        // Pin our overlay-only (cube_all + cutout ore texture) models as standalone models so
        // they get loaded, baked, and stitched into the block atlas even though no blockstate
        // references them. The baked BlockStateModel is resolved into OverlayModelRegistry at
        // BakingCompleted time.
        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            StandaloneModelKey<BlockStateModel> key = new StandaloneModelKey<>(() -> "aptores:overlay_" + type.name());
            event.register(key, SimpleUnbakedStandaloneModel.blockStateModel(type.overlayModelId()));
            OverlayModelRegistry.putKey(type, key);
        }
    }

    private void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        // The baking result's block-state map is mutable; swap each target ore's model for a
        // composite that samples its neighbors live.
        Map<BlockState, BlockStateModel> models = event.getBakingResult().blockStateModels();
        for (Map.Entry<BlockState, BlockStateModel> entry : models.entrySet()) {
            OreTypeDefinition type = OreTypeRegistry.byBlockId(BuiltInRegistries.BLOCK.getKey(entry.getKey().getBlock()));
            if (type != null) {
                entry.setValue(new AptOresModel(type, entry.getValue()));
            }
        }
    }

    private void onBakingCompleted(ModelEvent.BakingCompleted event) {
        // Resolve every standalone overlay key to its baked BlockStateModel now that baking has
        // finished, so the render hot path does a plain map lookup.
        StandaloneModelLoader.BakedModels standalone = event.getBakingResult().standaloneModels();
        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            StandaloneModelKey<BlockStateModel> key = OverlayModelRegistry.getKey(type);
            if (key != null) {
                BlockStateModel overlay = standalone.get(key);
                if (overlay != null) {
                    OverlayModelRegistry.putBaked(type, overlay);
                }
            }
        }
    }
}
