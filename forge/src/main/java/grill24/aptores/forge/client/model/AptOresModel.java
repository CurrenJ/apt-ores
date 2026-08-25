package grill24.aptores.forge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Purely-visual composite: the backdrop layer is the live-sampled neighbor block's own baked
 * model, the overlay layer is this ore's cutout fragment texture. No world state is written -
 * {@link #getModelData} just samples the six neighbors fresh every mesh rebuild, the same way
 * vanilla already re-triggers a neighborhood remesh for AO/connection-dependent rendering, so
 * this stays in sync automatically when a neighbor is placed or broken.
 *
 * <p>{@code vanillaOreModel} (the model this instance replaces) is kept only as a source of
 * truth for the particle icon; its geometry is never emitted. Item/inventory rendering is
 * unaffected by this class - as of 1.21.6 it's baked completely separately from block-state
 * models (see {@code BlockModelWrapper}/{@code ItemModel}), so the composite look is only
 * visible in-world.
 *
 * <p>Unlike NeoForge (which gained a dedicated {@code DynamicBlockStateModel} interface passing
 * {@code BlockAndTintGetter}/{@code BlockPos} straight into {@code collectParts}), regular Forge
 * 1.21.6 still routes per-block extra data through the older {@link ModelData} mechanism via
 * {@link net.minecraftforge.client.extensions.IForgeBlockStateModel} - so this class keeps the
 * "sample backdrop in getModelData, read it back out of ModelData later" indirection the
 * 1.21.1/1.21.4 ports used, just against the new {@link BlockStateModel}/{@link BlockModelPart}
 * types instead of {@code BakedModel}/{@code BakedQuad} lists.
 */
public class AptOresModel implements BlockStateModel {
    public static final ModelProperty<BlockState> BACKDROP_PROPERTY = new ModelProperty<>();

    private static final float OVERLAY_OFFSET = 0.001f;

    private final OreTypeDefinition oreType;
    private final BlockStateModel vanillaOreModel;
    private final BlockStateModel overlayModel;

    public AptOresModel(OreTypeDefinition oreType, BlockStateModel vanillaOreModel, BlockStateModel overlayModel) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
        this.overlayModel = overlayModel;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> parts) {
        // Fallback path for callers that bypass the ModelData-aware overload and query this
        // model with no world/position context. Falls back to the vanilla-model geometry (no
        // neighbor sampling is possible here).
        vanillaOreModel.collectParts(random, parts);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos,
                                            @NotNull BlockState state, @NotNull ModelData modelData) {
        BlockState backdrop = BackdropSampler.sample(level, pos);
        return modelData.derive().with(BACKDROP_PROPERTY, backdrop).build();
    }

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> dest, @NotNull ModelData data, @Nullable ChunkSectionLayer renderType) {
        BlockState backdrop = data.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        BlockStateModel backdropModel = getBackdropModel(backdrop);
        backdropModel.collectParts(random, dest, ModelData.EMPTY, renderType);

        if (renderType == null || renderType == ChunkSectionLayer.TRANSLUCENT) {
            List<BlockModelPart> overlayParts = new ArrayList<>();
            overlayModel.collectParts(random, overlayParts, ModelData.EMPTY, renderType);
            for (BlockModelPart part : overlayParts) {
                dest.add(new OverlayPart(part));
            }
        }
    }

    private BlockStateModel getBackdropModel(BlockState backdrop) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
    }

    @Override
    public Collection<ChunkSectionLayer> getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        BlockState backdrop = data.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        BlockStateModel backdropModel = getBackdropModel(backdrop);
        LinkedHashSet<ChunkSectionLayer> types = new LinkedHashSet<>(backdropModel.getRenderTypes(backdrop, rand, ModelData.EMPTY));
        types.add(ChunkSectionLayer.TRANSLUCENT);
        return types;
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return vanillaOreModel.particleIcon();
    }

    @Override
    public TextureAtlasSprite particleIcon(@NotNull ModelData data) {
        return vanillaOreModel.particleIcon(data);
    }

    /**
     * Wraps a single part of the baked overlay model: offsets its quads slightly outward along
     * their face normal (so the overlay doesn't z-fight with the backdrop layer directly beneath
     * it).
     */
    private static final class OverlayPart implements BlockModelPart {
        private final BlockModelPart delegate;

        private OverlayPart(BlockModelPart delegate) {
            this.delegate = delegate;
        }

        @Override
        public @NotNull List<BakedQuad> getQuads(@Nullable Direction side) {
            List<BakedQuad> quads = new ArrayList<>();
            for (BakedQuad quad : delegate.getQuads(side)) {
                quads.add(QuadHelper.offsetQuad(quad, side, OVERLAY_OFFSET));
            }
            return quads;
        }

        @Override
        public boolean useAmbientOcclusion() {
            return delegate.useAmbientOcclusion();
        }

        @Override
        public TextureAtlasSprite particleIcon() {
            return delegate.particleIcon();
        }
    }
}
