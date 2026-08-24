package grill24.aptores.fabric.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.fabric.client.OverlayModelRegistry;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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

    private final OreTypeDefinition oreType;
    private final BakedModel vanillaOreModel;

    public AptOresBakedModel(OreTypeDefinition oreType, BakedModel vanillaOreModel) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
    }

    private static RenderMaterial createOverlayMaterial() {
        var renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer == null) {
            return null;
        }
        return renderer.materialFinder()
            .blendMode(0, BlendMode.TRANSLUCENT)
            .disableDiffuse(0, true)
            .find();
    }

    private BakedModel getOverlayModel() {
        return OverlayModelRegistry.get(oreType);
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos,
                                Supplier<RandomSource> randomSupplier, RenderContext context) {
        BlockState backdrop = BackdropSampler.sample(blockView, pos);
        RandomSource random = randomSupplier.get();

        BakedModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        if (isRealFabricModel(backdropModel)) {
            ((FabricBakedModel) backdropModel).emitBlockQuads(blockView, backdrop, pos, randomSupplier, context);
        } else {
            emitVanillaQuads(backdropModel, backdrop, random, context, null);
        }

        BakedModel overlayModel = getOverlayModel();
        if (overlayModel != null && OVERLAY_MATERIAL != null) {
            if (isRealFabricModel(overlayModel)) {
                ((FabricBakedModel) overlayModel).emitBlockQuads(blockView, state, pos, randomSupplier, context);
            } else {
                emitVanillaQuads(overlayModel, state, random, context, OVERLAY_MATERIAL);
            }
        }
    }

    @Override
    public void emitItemQuads(ItemStack stack, Supplier<RandomSource> randomSupplier, RenderContext context) {
        // No neighbors to sample for an item in a hand/GUI - fall back to a plain stone backdrop.
        BlockState defaultBackdrop = Blocks.STONE.defaultBlockState();
        RandomSource random = randomSupplier.get();
        BakedModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(defaultBackdrop);
        if (isRealFabricModel(backdropModel)) {
            ((FabricBakedModel) backdropModel).emitItemQuads(stack, randomSupplier, context);
        } else if (backdropModel != null) {
            emitVanillaQuads(backdropModel, defaultBackdrop, random, context, null);
        }

        BakedModel overlayModel = getOverlayModel();
        if (isRealFabricModel(overlayModel)) {
            ((FabricBakedModel) overlayModel).emitItemQuads(stack, randomSupplier, context);
        } else if (overlayModel != null) {
            emitVanillaQuads(overlayModel, defaultBackdrop, random, context, OVERLAY_MATERIAL);
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

    private void emitVanillaQuads(BakedModel model, BlockState state, RandomSource random,
                                   RenderContext context, @Nullable RenderMaterial material) {
        for (Direction direction : Direction.values()) {
            for (BakedQuad quad : model.getQuads(state, direction, random)) {
                context.getEmitter().fromVanilla(quad, material, direction).emit();
            }
        }
        for (BakedQuad quad : model.getQuads(state, null, random)) {
            context.getEmitter().fromVanilla(quad, material, quad.getDirection()).emit();
        }
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
        // Fallback path for callers that bypass the Fabric Renderer API (e.g. some item-frame
        // or GUI previews). Uses a fixed stone backdrop since there's no world/position here.
        List<BakedQuad> quads = new ArrayList<>();
        BlockState backdrop = Blocks.STONE.defaultBlockState();
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
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return vanillaOreModel.getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ItemTransforms getTransforms() {
        return vanillaOreModel.getTransforms();
    }
}
