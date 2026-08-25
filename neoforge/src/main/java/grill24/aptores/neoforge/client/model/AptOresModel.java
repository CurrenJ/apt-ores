package grill24.aptores.neoforge.client.model;

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
import net.minecraft.util.TriState;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Purely-visual composite: the backdrop layer is the live-sampled neighbor block's own baked
 * model, the overlay layer is this ore's cutout fragment texture. No world state is written -
 * {@link #collectParts} just samples the six neighbors fresh every mesh rebuild, the same way
 * vanilla already re-triggers a neighborhood remesh for AO/connection-dependent rendering, so
 * this stays in sync automatically when a neighbor is placed or broken.
 *
 * <p>{@code vanillaOreModel} (the model this instance replaces) is kept only as a source of
 * truth for the particle icon; its geometry is never emitted. Item/inventory rendering is
 * unaffected by this class - as of 1.21.6 it's baked completely separately from block-state
 * models (see {@code BlockModelWrapper}/{@code ItemModel}), so the composite look is only
 * visible in-world.
 */
public class AptOresModel implements DynamicBlockStateModel {
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
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockModelPart> parts) {
        BlockState backdrop = BackdropSampler.sample(level, pos);

        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        backdropModel.collectParts(level, pos, backdrop, random, parts);

        List<BlockModelPart> overlayParts = new ArrayList<>();
        overlayModel.collectParts(level, pos, state, random, overlayParts);
        for (BlockModelPart part : overlayParts) {
            parts.add(new OverlayPart(part));
        }
    }

    @Override
    @Nullable
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        // Depends on live neighbor state - never cache.
        return null;
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return vanillaOreModel.particleIcon();
    }

    @Override
    public TextureAtlasSprite particleIcon(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return vanillaOreModel.particleIcon(level, pos, state);
    }

    /**
     * Wraps a single part of the baked overlay model: offsets its quads slightly outward along
     * their face normal (so the overlay doesn't z-fight with the backdrop layer directly beneath
     * it) and forces translucent rendering regardless of what render type the backdrop uses.
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

        @Override
        public ChunkSectionLayer getRenderType(BlockState state) {
            return ChunkSectionLayer.TRANSLUCENT;
        }

        @Override
        public TriState ambientOcclusion() {
            return delegate.ambientOcclusion();
        }
    }
}
