package grill24.aptores.fabric.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.fabric.client.OverlayModelRegistry;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Purely-visual, stateless composite: renders the neighbor-sampled backdrop block's own baked
 * model as the base layer, with this ore's cutout overlay on top. No world state is written or
 * read beyond a live lookup of the six neighboring block states every time this block is
 * (re)meshed - the same technique connected-texture mods use to pick a texture from neighbors.
 *
 * <p>The wrapped {@code vanillaOreModel} is kept only as a source of truth for particle icon and
 * item transforms (so break particles and inventory rendering still look correct); its geometry
 * is never emitted.
 */
public class AptOresBakedModel implements BakedModel, FabricBakedModel {
    private static final float OVERLAY_OFFSET = 0.0025f;
    private static final RenderMaterial OVERLAY_MATERIAL = createOverlayMaterial();
    private static final RenderMaterial DEFAULT_MATERIAL = createDefaultMaterial();

    private final OreTypeDefinition oreType;
    private final BakedModel vanillaOreModel;
    private final BlockState defaultBackdrop;

    /**
     * @param defaultBackdrop the stone/deepslate backdrop to use when there's no neighbor to
     *                        sample - i.e. the item/GUI icon (see {@link BackdropSampler#defaultBackdropFor(net.minecraft.resources.ResourceLocation)}).
     */
    public AptOresBakedModel(OreTypeDefinition oreType, BakedModel vanillaOreModel, BlockState defaultBackdrop) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
        this.defaultBackdrop = defaultBackdrop;
    }

    private static RenderMaterial createOverlayMaterial() {
        var renderer = Renderer.get();
        if (renderer == null) {
            return null;
        }
        return renderer.materialFinder()
            .blendMode(BlendMode.TRANSLUCENT)
            .disableDiffuse(true)
            .find();
    }

    /**
     * Indigo's {@code QuadEmitter.fromVanilla} NPEs if handed a {@code null} material (it doesn't
     * fall back to a standard one), so every quad we forward - including the plain backdrop
     * geometry - needs an explicit material.
     */
    private static RenderMaterial createDefaultMaterial() {
        var renderer = Renderer.get();
        if (renderer == null) {
            return null;
        }
        return renderer.materialFinder().find();
    }

    private BakedModel getOverlayModel() {
        return OverlayModelRegistry.get(oreType);
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockState state, BlockPos pos,
                                Supplier<RandomSource> randomSupplier, Predicate<@Nullable Direction> cullTest) {
        BlockState backdrop = BackdropSampler.sample(blockView, pos);
        RandomSource random = randomSupplier.get();

        BakedModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        if (isRealFabricModel(backdropModel)) {
            ((FabricBakedModel) backdropModel).emitBlockQuads(emitter, blockView, backdrop, pos, randomSupplier, cullTest);
        } else {
            emitVanillaQuads(emitter, backdropModel, backdrop, random, DEFAULT_MATERIAL);
        }

        BakedModel overlayModel = getOverlayModel();
        if (overlayModel != null && OVERLAY_MATERIAL != null) {
            if (isRealFabricModel(overlayModel)) {
                ((FabricBakedModel) overlayModel).emitBlockQuads(emitter, blockView, state, pos, randomSupplier, cullTest);
            } else {
                emitVanillaQuads(emitter, overlayModel, state, random, OVERLAY_MATERIAL);
            }
        }
    }

    @Override
    public void emitItemQuads(QuadEmitter emitter, Supplier<RandomSource> randomSupplier) {
        // No neighbors to sample for an item in a hand/GUI - fall back to this ore's own
        // stone/deepslate default instead of a live sample.
        RandomSource random = randomSupplier.get();
        BakedModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(defaultBackdrop);
        if (isRealFabricModel(backdropModel)) {
            ((FabricBakedModel) backdropModel).emitItemQuads(emitter, randomSupplier);
        } else if (backdropModel != null) {
            emitVanillaQuads(emitter, backdropModel, defaultBackdrop, random, DEFAULT_MATERIAL);
        }

        BakedModel overlayModel = getOverlayModel();
        if (isRealFabricModel(overlayModel)) {
            ((FabricBakedModel) overlayModel).emitItemQuads(emitter, randomSupplier);
        } else if (overlayModel != null) {
            emitVanillaQuads(emitter, overlayModel, defaultBackdrop, random, OVERLAY_MATERIAL);
        }
    }

    /**
     * Fabric's renderer API mixes {@link FabricBakedModel} onto every {@link BakedModel} (with
     * {@code isVanillaAdapter()} defaulting to {@code true} and a no-op default
     * {@code emitBlockQuads}), so a plain {@code instanceof FabricBakedModel} check is always
     * true and would silently drop every quad for a plain vanilla model like stone. Only delegate
     * to {@code emitBlockQuads}/{@code emitItemQuads} for models that genuinely opted in.
     */
    private static boolean isRealFabricModel(@Nullable BakedModel model) {
        return model instanceof FabricBakedModel fabricModel && !fabricModel.isVanillaAdapter();
    }

    private void emitVanillaQuads(QuadEmitter emitter, BakedModel model, BlockState state, RandomSource random,
                                   @Nullable RenderMaterial material) {
        for (Direction direction : Direction.values()) {
            for (BakedQuad quad : model.getQuads(state, direction, random)) {
                emitter.fromVanilla(quad, material, direction).emit();
            }
        }
        for (BakedQuad quad : model.getQuads(state, null, random)) {
            emitter.fromVanilla(quad, material, quad.getDirection()).emit();
        }
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
        // Fallback path for callers that bypass the Fabric Renderer API (e.g. some item-frame
        // or GUI previews). Uses this ore's own stone/deepslate default since there's no
        // world/position here.
        List<BakedQuad> quads = new ArrayList<>();
        BlockState backdrop = defaultBackdrop;
        BakedModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        if (backdropModel != null) {
            quads.addAll(backdropModel.getQuads(backdrop, side, rand));
        }

        BakedModel overlayModel = getOverlayModel();
        if (overlayModel != null) {
            for (BakedQuad quad : overlayModel.getQuads(state, side, rand)) {
                quads.add(QuadHelper.offsetQuad(quad, side, OVERLAY_OFFSET));
            }
        }
        return quads;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return vanillaOreModel.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return vanillaOreModel.getTransforms();
    }
}
