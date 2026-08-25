package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.SimpleModelWrapper;
import net.minecraft.client.renderer.block.model.SingleVariant;
import net.minecraft.client.renderer.item.BlockModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.resources.Identifier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * No blocks, items, or block entities are registered anywhere in this project, and nothing runs
 * on a dedicated server - this class is restricted to {@link Dist#CLIENT} so Forge never loads it
 * server-side. Ore rendering is achieved purely by post-processing the vanilla model bake result -
 * swapping each target ore's baked model for a composite that samples its neighbors live - the
 * same technique connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 *
 * <p>Unlike NeoForge, (Minecraft)Forge exposes no {@code ModelEvent.RegisterStandalone} (or
 * equivalent) hook for pinning a model that no blockstate or item references. To still get our
 * overlay-only models loaded and baked, each one is shadowed by a throwaway client item
 * definition (see {@code assets/aptores/items/overlay_*.json}) - the vanilla per-item model JSON
 * loader indexes those by file path regardless of whether a real item exists with that id, so
 * they show up in {@link net.minecraft.client.resources.model.ModelBakery.BakingResult#itemStackModels()}
 * without needing any block/item registration.
 *
 * <p>As of 1.21.11's model rework, the resulting {@link ItemModel} for a {@code "minecraft:model"}
 * definition is a {@link BlockModelWrapper} whose baked quad list is a private field with no
 * public accessor (unlike 1.21.4's, which exposed the wrapped {@code BakedModel} directly) - see
 * {@link #extractQuads}.
 */
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
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

        Map<net.minecraft.world.level.block.state.BlockState, BlockStateModel> models = event.getResults().blockStateModels();
        Map<Identifier, ItemModel> itemModels = event.getResults().itemStackModels();

        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            ItemModel shadowItemModel = itemModels.get(overlayItemId(type));
            BlockStateModel overlayModel = shadowItemModel instanceof BlockModelWrapper wrapper
                ? wrapAsBlockStateModel(extractQuads(wrapper))
                : null;
            if (overlayModel == null) {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked", type.name());
                continue;
            }
            OverlayModelRegistry.put(type, overlayModel);
        }

        for (Map.Entry<net.minecraft.world.level.block.state.BlockState, BlockStateModel> entry : models.entrySet()) {
            OreTypeDefinition type = OreTypeRegistry.byBlockId(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(entry.getKey().getBlock()));
            if (type != null && OverlayModelRegistry.get(type) != null) {
                entry.setValue(new AptOresModel(type, entry.getValue()));
            }
        }
    }

    /**
     * Rebuilds a {@link BlockModelPart} (and wraps it as a single-variant {@link BlockStateModel})
     * from the flattened quad list captured off the shadow item's {@link BlockModelWrapper}. Every
     * quad from a plain {@code cube_all} bake carries its face's {@link BakedQuad#direction()}, so
     * bucketing by that direction reconstructs an equivalent {@link QuadCollection}.
     */
    private static BlockStateModel wrapAsBlockStateModel(List<BakedQuad> quads) {
        if (quads == null || quads.isEmpty()) {
            return null;
        }
        QuadCollection.Builder builder = new QuadCollection.Builder();
        for (BakedQuad quad : quads) {
            if (quad.direction() != null) {
                builder.addCulledFace(quad.direction(), quad);
            } else {
                builder.addUnculledFace(quad);
            }
        }
        BlockModelPart part = new SimpleModelWrapper(builder.build(), true, quads.get(0).sprite());
        return new SingleVariant(part);
    }

    /**
     * Reflectively reads {@link BlockModelWrapper}'s private {@code quads} field. Forge's own
     * {@code IForgeBlockStateModel}/{@code IForgeBlockModelPart} extensions don't expose this, and
     * there's no other public way to recover baked geometry from an already-baked
     * {@link ItemModel} on this loader - see the class javadoc.
     */
    private static List<BakedQuad> extractQuads(BlockModelWrapper wrapper) {
        try {
            Field field = BlockModelWrapper.class.getDeclaredField("quads");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BakedQuad> quads = (List<BakedQuad>) field.get(wrapper);
            return quads;
        } catch (ReflectiveOperationException e) {
            AptOres.LOGGER.error("Apt Ores: failed to extract quads from shadow item model", e);
            return null;
        }
    }
}
