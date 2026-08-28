package grill24.aptores.neoforge;

import grill24.aptores.AptOres;
import grill24.aptores.AptOresConfig;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.OreTypeLoader;
import grill24.aptores.OreTypeRegistry;
import grill24.aptores.neoforge.client.model.AptOresModel;
import grill24.aptores.neoforge.client.model.QuadHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.SimpleModelWrapper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelBaker;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelLoader;

import java.util.HashMap;
import java.util.Map;

/**
 * The entire mod: no blocks, items, or block entities are registered anywhere in this project,
 * and nothing runs on a dedicated server (this class only loads on {@link Dist#CLIENT}). Ore
 * rendering is achieved purely by post-processing the vanilla model bake result - swapping each
 * target ore's baked model for a composite that samples its neighbors live - the same technique
 * connected-texture mods (e.g. Continuity) use for neighbor-aware rendering.
 *
 * <p>1.21.5 replaced the old {@code ModelEvent.RegisterAdditional} / raw
 * {@code Map<ResourceLocation, BakedModel>} standalone-model mechanism with a typed
 * {@link StandaloneModelKey}/{@link net.neoforged.neoforge.client.model.standalone.UnbakedStandaloneModel}
 * system (see {@link ModelEvent.RegisterStandalone} and {@link StandaloneModelLoader}). We use it
 * to pin each ore's overlay-only (cube_all + cutout ore texture) model as a baked
 * {@link QuadCollection}, then wrap it into a single translucent {@link BlockModelPart} once, at
 * bake time, offset slightly outward so it doesn't z-fight with the backdrop layer beneath it.
 */
@EventBusSubscriber(modid = AptOres.MOD_ID, value = Dist.CLIENT)
@Mod(value = AptOres.MOD_ID, dist = Dist.CLIENT)
public class AptOresNeoForgeClient implements IModBusEvent {
    private static final float OVERLAY_OFFSET = 0.001f;

    private static final Map<ResourceLocation, StandaloneModelKey<QuadCollection>> OVERLAY_KEYS = new HashMap<>();

    @SubscribeEvent
    public static void onRegisterStandalone(ModelEvent.RegisterStandalone event) {
        AptOresConfig.load(FMLPaths.CONFIGDIR.get());
        OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()));

        OVERLAY_KEYS.clear();
        for (OreTypeDefinition type : OreTypeRegistry.all()) {
            StandaloneModelKey<QuadCollection> key = new StandaloneModelKey<>(type.overlayModelId());
            OVERLAY_KEYS.put(type.overlayModelId(), key);
            event.register(key, StandaloneModelBaker.quadCollection());
        }
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ModelBakery.BakingResult bakingResult = event.getBakingResult();
        Map<BlockState, BlockStateModel> models = bakingResult.blockStateModels();

        for (Map.Entry<BlockState, BlockStateModel> entry : models.entrySet()) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(entry.getKey().getBlock());
            OreTypeDefinition type = OreTypeRegistry.byBlockId(blockId);
            if (type == null) {
                continue;
            }

            StandaloneModelKey<QuadCollection> key = OVERLAY_KEYS.get(type.overlayModelId());
            QuadCollection overlayQuads = key != null ? bakingResult.standaloneModels().get(key) : null;
            if (overlayQuads == null) {
                AptOres.LOGGER.warn("Apt Ores: overlay model for {} was not baked; leaving {} untouched", type.name(), blockId);
                continue;
            }

            entry.setValue(new AptOresModel(type, entry.getValue(), buildOverlayPart(overlayQuads)));
        }
    }

    private static BlockModelPart buildOverlayPart(QuadCollection original) {
        QuadCollection.Builder builder = new QuadCollection.Builder();
        TextureAtlasSprite particle = null;

        for (Direction direction : Direction.values()) {
            for (BakedQuad quad : original.getQuads(direction)) {
                if (particle == null) {
                    particle = quad.sprite();
                }
                builder.addCulledFace(direction, QuadHelper.offsetQuad(quad, direction, OVERLAY_OFFSET));
            }
        }
        for (BakedQuad quad : original.getQuads(null)) {
            if (particle == null) {
                particle = quad.sprite();
            }
            builder.addUnculledFace(QuadHelper.offsetQuad(quad, null, OVERLAY_OFFSET));
        }

        return new SimpleModelWrapper(builder.build(), true, particle, RenderType.translucent());
    }
}
