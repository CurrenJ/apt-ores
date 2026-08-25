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
 * truth for the particle icon; its geometry is never emitted. {@code overlayModel} is the
 * already-baked overlay geometry, fetched via the synthetic-blockstate trick in
 * {@code AptOresForgeClient} (regular Forge dropped {@code ModelEvent.RegisterAdditional} and
 * never exposed an equivalent standalone-model facility - see {@code docs/DEVELOPMENT.md}).
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

    /** Context-free fallback (no world/position available) - matches item/inventory rendering. */
    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> parts) {
        collectParts(random, parts, ModelData.EMPTY, null);
    }

    /** IForgeBlockStateModel extension point: stash the sampled backdrop for collectParts to read back. */
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        BlockState backdrop = BackdropSampler.sample(level, pos);
        return modelData.derive().with(BACKDROP_PROPERTY, backdrop).build();
    }

    /** IForgeBlockStateModel extension point: the real, world-aware part-collection path. */
    public void collectParts(RandomSource random, List<BlockModelPart> dest, ModelData data, @Nullable ChunkSectionLayer renderType) {
        BlockState backdrop = data.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        // Delegate to the backdrop's own model in the same render layer we were asked for -
        // collectParts(random, dest) (layer-blind) would otherwise re-add the backdrop's full
        // geometry on every layer pass, duplicating its solid quads into the translucent pass
        // too and rendering as a black silhouette on top of the correct layer.
        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        backdropModel.collectParts(random, dest, ModelData.EMPTY, renderType);

        // Unlike NeoForge's DynamicBlockStateModel (one collectParts call per mesh, with each
        // part self-reporting its own render layer for the compiler to bucket by), Forge's
        // IForgeBlockStateModel calls this method once per requested layer and expects each call
        // to return only that layer's content - collectParts(random, dest) (the layer-blind
        // default most plain models fall back to) already ignores renderType for the backdrop
        // above, but the overlay is entirely our own content, so it must only be added for the
        // translucent pass (or the layer-blind/null fallback), never the solid one - otherwise
        // its half-transparent quads get rasterized into the solid buffer, which doesn't
        // alpha-test/blend them, rendering as a black silhouette over the whole face.
        if (renderType == null || renderType == ChunkSectionLayer.TRANSLUCENT) {
            for (BlockModelPart part : overlayModel.collectParts(random)) {
                dest.add(new OverlayPart(part));
            }
        }
    }

    /**
     * IForgeBlockStateModel extension point: without this override, the default implementation
     * ({@code ItemBlockRenderTypes.getRenderLayers(state)}) reports only the wrapped vanilla ore
     * block's own single render layer - never the backdrop's (sampled per-position, unknown to
     * that static lookup) or the overlay's translucent layer. The chunk compiler treats this as an
     * advertisement of which layers actually have content and skips collecting any layer not
     * listed here, so without it the solid backdrop is never even requested and only the
     * translucent overlay draws - i.e. exactly the "solid black background" bug this fixes.
     */
    public Collection<ChunkSectionLayer> getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        BlockState backdrop = data.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        Collection<ChunkSectionLayer> layers = new LinkedHashSet<>(backdropModel.getRenderTypes(backdrop, rand, ModelData.EMPTY));
        layers.add(ChunkSectionLayer.TRANSLUCENT);
        return layers;
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return vanillaOreModel.particleIcon();
    }

    /** Wraps one of the overlay's own parts, offsetting its quads and forcing the translucent chunk layer. */
    private static final class OverlayPart implements BlockModelPart {
        private final BlockModelPart delegate;

        OverlayPart(BlockModelPart delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<BakedQuad> getQuads(Direction direction) {
            List<BakedQuad> raw = delegate.getQuads(direction);
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
            return delegate.particleIcon();
        }

        /** Force this part onto the translucent chunk layer regardless of the backdrop's own layer. */
        public ChunkSectionLayer layer() {
            return ChunkSectionLayer.TRANSLUCENT;
        }
    }
}
