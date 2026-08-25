package grill24.aptores.forge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.QuadHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.SimpleModelWrapper;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Purely-visual, stateless composite: renders the neighbor-sampled backdrop block's own baked
 * block-state model as the base layer, with this ore's cutout overlay on top. No world state is
 * written or read beyond a live lookup of the six neighboring block states every time this block
 * is (re)meshed - the same technique connected-texture mods use to pick a texture from neighbors.
 *
 * <p>Regular Forge's mesher doesn't thread a {@code BlockAndTintGetter}/{@code BlockPos} straight
 * into {@code collectParts} the way NeoForge's {@code DynamicBlockStateModel} does; instead it
 * threads Forge's own {@link ModelData} through {@link #getModelData}, which fires with position
 * available and is the place to sample the backdrop and stash it in {@link #BACKDROP_PROPERTY}
 * for the later {@link #collectParts(RandomSource, List, ModelData, ChunkSectionLayer)} call to
 * read back out.
 */
public class AptOresModel implements BlockStateModel {
    public static final ModelProperty<BlockState> BACKDROP_PROPERTY = new ModelProperty<>();
    private static final float OVERLAY_OFFSET = 0.001f;

    private final OreTypeDefinition oreType;
    private final BlockStateModel delegate;
    private final BlockStateModel overlayModel;

    public AptOresModel(OreTypeDefinition oreType, BlockStateModel delegate, BlockStateModel overlayModel) {
        this.oreType = oreType;
        this.delegate = delegate;
        this.overlayModel = overlayModel;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> parts) {
        // Position-independent fallback (particles, inventory context): no ModelData available,
        // so sample nothing and fall back to the default backdrop.
        collectParts(random, parts, BackdropSampler.DEFAULT_BACKDROP, null);
    }

    @Override
    public List<BlockModelPart> collectParts(RandomSource random, ModelData data, ChunkSectionLayer renderType) {
        List<BlockModelPart> parts = new ArrayList<>();
        collectParts(random, parts, data, renderType);
        return parts;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> dest, ModelData data, ChunkSectionLayer renderType) {
        BlockState backdrop = data.get(BACKDROP_PROPERTY);
        collectParts(random, dest, backdrop != null ? backdrop : BackdropSampler.DEFAULT_BACKDROP, renderType);
    }

    private void collectParts(RandomSource random, List<BlockModelPart> dest, BlockState backdrop, ChunkSectionLayer renderType) {
        // Backdrop: delegate to the sampled neighbor's own model, in the same render layer we
        // were asked for - collectParts(random, dest) (layer-blind) would otherwise re-add the
        // backdrop's full geometry on every layer pass, duplicating its solid quads into the
        // translucent pass too and rendering as a black silhouette on top of the correct layer.
        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        backdropModel.collectParts(random, dest, ModelData.EMPTY, renderType);

        // Overlay: this ore's cutout fragments, offset slightly outward along each face so they
        // don't z-fight with the backdrop layer directly beneath them. Unlike NeoForge's
        // DynamicBlockStateModel (one collectParts call per mesh, with each part self-reporting
        // its own render layer for the compiler to bucket by), Forge's IForgeBlockStateModel
        // calls this method once per requested layer and expects each call to return only that
        // layer's content, so the overlay must only be added for the translucent pass (or the
        // layer-blind/null fallback) - otherwise its half-transparent quads get rasterized into
        // the solid buffer, which doesn't alpha-test/blend them, rendering as a black silhouette
        // over the whole face.
        if (overlayModel != null && (renderType == null || renderType == ChunkSectionLayer.TRANSLUCENT)) {
            List<BlockModelPart> overlayParts = new ArrayList<>();
            overlayModel.collectParts(random, overlayParts);
            for (BlockModelPart part : overlayParts) {
                dest.add(offset(part));
            }
        }
    }

    /** Rebuilds a baked part's quad collection offset outward, forcing it onto the translucent
     * layer regardless of the overlay model's own declared layer (its {@code cube_all} parent has
     * no explicit render_type, so it would otherwise inherit a non-translucent default). */
    private static BlockModelPart offset(BlockModelPart part) {
        if (part instanceof SimpleModelWrapper simple) {
            QuadCollection offsetQuads = QuadHelper.offset(simple.quads(), OVERLAY_OFFSET);
            return new SimpleModelWrapper(offsetQuads, simple.useAmbientOcclusion(), simple.particleIcon(),
                ChunkSectionLayer.TRANSLUCENT, simple.layerFast());
        }
        return part;
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos,
                                            @NotNull BlockState state, @NotNull ModelData modelData) {
        BlockState backdrop = BackdropSampler.sample(level, pos);
        return modelData.derive().with(BACKDROP_PROPERTY, backdrop).build();
    }

    @Override
    public Collection<ChunkSectionLayer> getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        BlockState backdrop = data.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        Collection<ChunkSectionLayer> layers = new LinkedHashSet<>(backdropModel.getRenderTypes(backdrop, rand, ModelData.EMPTY));

        // The rebuilt overlay part is always forced onto TRANSLUCENT (see offset()) regardless of
        // what the overlay model's own parts individually declare, so advertise that directly
        // rather than inspecting their (possibly different) natural layer.
        if (overlayModel != null) {
            layers.add(ChunkSectionLayer.TRANSLUCENT);
        }

        return layers;
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return delegate.particleIcon();
    }
}
