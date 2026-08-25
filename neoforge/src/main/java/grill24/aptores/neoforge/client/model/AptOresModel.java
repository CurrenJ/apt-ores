package grill24.aptores.neoforge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.neoforge.client.OverlayModelRegistry;
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
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Purely-visual, stateless composite: renders the neighbor-sampled backdrop block's own baked
 * block-state model as the base layer, with this ore's cutout overlay on top. No world state is
 * written or read beyond a live lookup of the six neighboring block states every time this block
 * is (re)meshed - the same technique connected-texture mods use to pick a texture from neighbors.
 *
 * <p>NeoForge's mesher dispatches every block through the position-aware 5-arg
 * {@link #collectParts(BlockAndTintGetter, BlockPos, BlockState, RandomSource, List)} (the
 * {@link DynamicBlockStateModel} contract). The wrapped {@code delegate} (the vanilla ore model
 * this instance replaces) is kept only as a source of truth for particles, break progress, and the
 * position-independent fallback path ({@link DelegateBlockStateModel#collectParts}); its geometry
 * is not emitted by default.
 */
public class AptOresModel extends DelegateBlockStateModel implements DynamicBlockStateModel {
    private static final float OVERLAY_OFFSET = 0.001f;

    private final OreTypeDefinition oreType;

    public AptOresModel(OreTypeDefinition oreType, BlockStateModel delegate) {
        super(delegate);
        this.oreType = oreType;
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        // Backdrop: sample the neighbor block's own model and emit its parts first. Delegate to
        // the position-aware path when the neighbor is itself dynamic (e.g. another adapted ore),
        // otherwise fall back to the position-independent collect.
        BlockState backdrop = BackdropSampler.sample(level, pos, state);
        BlockStateModel backdropModel = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(backdrop);
        if (backdropModel != null) {
            if (backdropModel instanceof DynamicBlockStateModel dynamic) {
                dynamic.collectParts(level, pos, backdrop, random, parts);
            } else {
                backdropModel.collectParts(random, parts);
            }
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
