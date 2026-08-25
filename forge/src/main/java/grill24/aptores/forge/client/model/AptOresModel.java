package grill24.aptores.forge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.QuadHelper;
import grill24.aptores.forge.client.OverlayModelRegistry;
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

    public AptOresModel(OreTypeDefinition oreType, BlockStateModel delegate) {
        this.oreType = oreType;
        this.delegate = delegate;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> parts) {
        // Position-independent fallback (particles, inventory context): no ModelData available,
        // so sample nothing and fall back to the default backdrop.
        collectParts(random, parts, BackdropSampler.DEFAULT_BACKDROP);
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
        collectParts(random, dest, backdrop != null ? backdrop : BackdropSampler.DEFAULT_BACKDROP);
    }

    private void collectParts(RandomSource random, List<BlockModelPart> dest, BlockState backdrop) {
        // Backdrop: delegate to the sampled neighbor's own model.
        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        backdropModel.collectParts(random, dest, ModelData.EMPTY, null);

        // Overlay: this ore's cutout fragments, offset slightly outward along each face so they
        // don't z-fight with the backdrop layer directly beneath them.
        BlockStateModel overlayModel = OverlayModelRegistry.get(oreType);
        if (overlayModel != null) {
            List<BlockModelPart> overlayParts = new ArrayList<>();
            overlayModel.collectParts(random, overlayParts);
            for (BlockModelPart part : overlayParts) {
                dest.add(offset(part));
            }
        }
    }

    private static BlockModelPart offset(BlockModelPart part) {
        if (part instanceof SimpleModelWrapper simple) {
            QuadCollection offsetQuads = QuadHelper.offset(simple.quads(), OVERLAY_OFFSET);
            return new SimpleModelWrapper(offsetQuads, simple.useAmbientOcclusion(), simple.particleIcon(), simple.layer(), simple.layerFast());
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

        BlockStateModel overlayModel = OverlayModelRegistry.get(oreType);
        if (overlayModel != null) {
            for (BlockModelPart part : overlayModel.collectParts(rand)) {
                ChunkSectionLayer layer = part.layer();
                if (layer != null) {
                    layers.add(layer);
                }
            }
        }

        return layers;
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return delegate.particleIcon();
    }
}
