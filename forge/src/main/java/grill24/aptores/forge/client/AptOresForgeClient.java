package grill24.aptores.forge.client;

import grill24.aptores.AptOres;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.forge.client.model.AptOresModel;
import grill24.aptores.forge.client.model.QuadHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.SimpleModelWrapper;
import net.minecraft.client.renderer.item.BlockModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * No blocks, items, or block entities are registered anywhere in this project, and nothing runs
 * on a dedicated server - this class is restricted to {@link Dist#CLIENT} so Forge never loads it
 * server-side. Ore rendering is achieved purely by post-processing the vanilla model bake result -
 * swapping each target ore's block state model for a composite that samples its neighbors live -
 * the same technique connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 *
 * <p>Unlike NeoForge (which grew a proper typed "standalone model" registration facility in
 * 1.21.5, see {@code neoforge/.../AptOresNeoForgeClient}), regular (Minecraft)Forge 1.21.5 still
 * exposes no hook at all for pinning a model that no blockstate or item references - the same gap
 * documented for Forge 1.21.4 in {@code docs/PORTING.md}. So each overlay-only model is still
 * shadowed by a throwaway client item definition (see {@code assets/aptores/items/overlay_*.json});
 * the vanilla per-item model JSON loader indexes those by file path regardless of whether a real
 * item exists with that id, so they show up in {@link ModelBakery.BakingResult#itemStackModels()}
 * without needing any block/item registration.
 *
 * <p>The 1.21.5 item-model rework replaced the old {@code BlockModelWrapper.model} field (a plain
 * {@code BakedModel}) with a private {@code List<BakedQuad> quads} field and no public accessor -
 * confirmed by decompiling the real 55.1.0 jar, not assumed. With no other public way to recover
 * the raw quads from an already-baked {@link BlockModelWrapper} and no direct on-demand baking
 * entry point exposed by {@link ModelEvent.ModifyBakingResult} on this loader, a narrow reflective
 * field read is the least invasive option (see {@code docs/PORTING.md} §4's guidance to prefer
 * this over a mixin when a real gap - not a naming difference - is confirmed).
 */
@Mod.EventBusSubscriber(modid = AptOres.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class AptOresForgeClient {
    private static final float OVERLAY_OFFSET = 0.001f;

    private static volatile Field quadsField;

    /** The throwaway item-model id shadowing {@code type.overlayModelId()} (see class javadoc). */
    private static ResourceLocation overlayItemId(OreTypeDefinition type) {
        ResourceLocation modelId = type.overlayModelId();
        return ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), modelId.getPath().replaceFirst("^block/", ""));
    }

    @SuppressWarnings("unchecked")
    private static List<BakedQuad> getWrappedQuads(BlockModelWrapper wrapper) {
        try {
            Field field = quadsField;
            if (field == null) {
                field = BlockModelWrapper.class.getDeclaredField("quads");
                field.setAccessible(true);
                quadsField = field;
            }
            return (List<BakedQuad>) field.get(wrapper);
        } catch (ReflectiveOperationException e) {
            AptOres.LOGGER.warn("Apt Ores: could not read baked quads from the shadow overlay item model", e);
            return List.of();
        }
    }

    private static BlockModelPart buildOverlayPart(List<BakedQuad> rawQuads) {
        QuadCollection.Builder builder = new QuadCollection.Builder();
        TextureAtlasSprite particle = null;

        for (BakedQuad quad : rawQuads) {
            if (particle == null) {
                particle = quad.sprite();
            }
            Direction direction = quad.direction();
            BakedQuad offset = QuadHelper.offsetQuad(quad, direction, OVERLAY_OFFSET);
            if (direction != null) {
                builder.addCulledFace(direction, offset);
            } else {
                builder.addUnculledFace(offset);
            }
        }

        return new SimpleModelWrapper(builder.build(), true, particle);
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));

        ModelBakery.BakingResult bakingResult = event.getResults();
        Map<BlockState, BlockStateModel> models = bakingResult.blockStateModels();
        Map<ResourceLocation, ItemModel> itemModels = bakingResult.itemStackModels();

        for (Map.Entry<BlockState, BlockStateModel> entry : models.entrySet()) {
            BlockState state = entry.getKey();
            ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
            OreTypeDefinition type = OreTypeRegistry.byBlockId(blockId);
            if (type == null) {
                continue;
            }

            ItemModel overlayItemModel = itemModels.get(overlayItemId(type));
            List<BakedQuad> rawQuads = overlayItemModel instanceof BlockModelWrapper wrapper
                ? getWrappedQuads(wrapper)
                : null;
            if (rawQuads == null || rawQuads.isEmpty()) {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked; leaving {} untouched", type.name(), blockId);
                continue;
            }

            entry.setValue(new AptOresModel(type, entry.getValue(), buildOverlayPart(rawQuads)));
        }
    }
}
