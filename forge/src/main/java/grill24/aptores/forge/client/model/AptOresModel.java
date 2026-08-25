package grill24.aptores.forge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.forge.client.OverlayModelRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Purely-visual, stateless composite: renders the neighbor-sampled backdrop block's own baked
 * block-state model as the base layer, with this ore's cutout overlay on top. No world state is
 * written - {@link #getModelData} just samples the six neighbors fresh every mesh rebuild, the
 * same way vanilla already re-triggers a neighborhood remesh for AO/connection-dependent
 * rendering, so this stays in sync automatically when a neighbor is placed or broken.
 *
 * <p>Forge's mesher dispatches every block through the ModelData-aware {@code collectParts} (the
 * {@link net.minecraftforge.client.extensions.IForgeBlockStateModel} contract): it calls
 * {@link #getModelData} first, then feeds the result into {@link #collectParts(RandomSource, List,
 * ModelData)}. The wrapped {@code vanillaOreModel} (the model this instance replaces) is kept only
 * as a source of truth for particles, break progress, and the position-independent fallback path;
 * its geometry is not emitted by default.
 */
public class AptOresModel implements BlockStateModel {
    public static final ModelProperty<BlockState> BACKDROP_PROPERTY = new ModelProperty<>();

    private static final float OVERLAY_OFFSET = 0.001f;

    private final OreTypeDefinition oreType;
    private final BlockStateModel vanillaOreModel;

    public AptOresModel(OreTypeDefinition oreType, BlockStateModel vanillaOreModel) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        // Position-independent fallback (item rendering, particles): plain vanilla ore.
        vanillaOreModel.collectParts(random, parts);
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts, ModelData modelData) {
        BlockState backdrop = modelData.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        // Backdrop: emit the sampled neighbor block's own baked model's parts. Its model is
        // position-independent (a pure function of the block state), so the 2-arg collect is all
        // we need even though the renderer is position-aware.
        BlockStateModel backdropModel = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(backdrop);
        if (backdropModel != null) {
            backdropModel.collectParts(random, parts);
        }

        // Overlay: emit this ore's cutout fragments slightly outward along each face so they
        // don't z-fight with the backdrop layer directly beneath them. The offset is applied at
        // quad time, leaving the shared baked overlay model untouched.
        BlockStateModel overlayModel = OverlayModelRegistry.get(oreType);
        if (overlayModel != null) {
            List<BlockStateModelPart> overlayParts = new ArrayList<>();
            overlayModel.collectParts(random, overlayParts);
            for (BlockStateModelPart part : overlayParts) {
                parts.add(new OffsetBlockStateModelPart(part, OVERLAY_OFFSET));
            }
        }
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        BlockState backdrop = BackdropSampler.sample(level, pos);
        return modelData.derive().with(BACKDROP_PROPERTY, backdrop).build();
    }

    @Override
    public Material.Baked particleMaterial() {
        return vanillaOreModel.particleMaterial();
    }

    @Override
    public int materialFlags() {
        return vanillaOreModel.materialFlags();
    }

    /** Wraps a baked part so its quads are emitted offset outward along the face normal. */
    private record OffsetBlockStateModelPart(BlockStateModelPart delegate, float offset) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(@Nullable Direction direction) {
            return delegate.getQuads(direction).stream()
                .map(quad -> QuadHelper.offsetQuad(quad, direction, offset))
                .toList();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return delegate.useAmbientOcclusion();
        }

        @Override
        public Material.Baked particleMaterial() {
            return delegate.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return delegate.materialFlags();
        }
    }
}
