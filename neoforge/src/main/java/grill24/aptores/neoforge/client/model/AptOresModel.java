package grill24.aptores.neoforge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;

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
 * truth for the particle icon; its geometry is never emitted. {@code overlayQuads} is the
 * already-baked overlay geometry (a {@link QuadCollection}, fetched via NeoForge's standalone
 * model mechanism - see {@code AptOresNeoForgeClient}).
 */
public class AptOresModel implements DynamicBlockStateModel {
    private static final float OVERLAY_OFFSET = 0.001f;

    private final OreTypeDefinition oreType;
    private final BlockStateModel vanillaOreModel;
    private final QuadCollection overlayQuads;

    public AptOresModel(OreTypeDefinition oreType, BlockStateModel vanillaOreModel, QuadCollection overlayQuads) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
        this.overlayQuads = overlayQuads;
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockModelPart> parts) {
        BlockState backdrop = BackdropSampler.sample(level, pos);
        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        backdropModel.collectParts(level, pos, backdrop, random, parts);

        parts.add(new OverlayPart(overlayQuads, vanillaOreModel.particleIcon()));
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return vanillaOreModel.particleIcon();
    }

    /** A single-purpose {@link BlockModelPart} wrapping the offset, always-translucent overlay geometry. */
    private static final class OverlayPart implements BlockModelPart {
        private final QuadCollection quads;
        private final TextureAtlasSprite particleIcon;

        OverlayPart(QuadCollection quads, TextureAtlasSprite particleIcon) {
            this.quads = quads;
            this.particleIcon = particleIcon;
        }

        @Override
        public List<BakedQuad> getQuads(Direction direction) {
            List<BakedQuad> raw = quads.getQuads(direction);
            List<BakedQuad> offset = new ArrayList<>(raw.size());
            for (BakedQuad quad : raw) {
                offset.add(QuadHelper.offsetQuad(quad, direction, OVERLAY_OFFSET));
            }
            return offset;
        }

        @Override
        public boolean useAmbientOcclusion() {
            return false;
        }

        @Override
        public TextureAtlasSprite particleIcon() {
            return particleIcon;
        }

        /** Force this part onto the translucent chunk layer regardless of the backdrop's own layer. */
        public ChunkSectionLayer getRenderType(BlockState state) {
            return ChunkSectionLayer.TRANSLUCENT;
        }
    }
}
