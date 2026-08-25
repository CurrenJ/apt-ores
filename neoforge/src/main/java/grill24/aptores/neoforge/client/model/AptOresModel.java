package grill24.aptores.neoforge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.QuadHelper;
import grill24.aptores.neoforge.client.OverlayModelRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.SimpleModelWrapper;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;

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
 * this instance replaces) is kept only as a source of truth for particles and the position-
 * independent fallback path ({@link DelegateBlockStateModel#collectParts}); its geometry is not
 * emitted by default.
 */
public class AptOresModel extends DelegateBlockStateModel implements DynamicBlockStateModel {
    private static final float OVERLAY_OFFSET = 0.001f;

    private final OreTypeDefinition oreType;

    public AptOresModel(OreTypeDefinition oreType, BlockStateModel delegate) {
        super(delegate);
        this.oreType = oreType;
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockModelPart> parts) {
        // Backdrop: sample the neighbor block's own model and emit its parts first. Delegate to
        // the position-aware path when the neighbor is itself dynamic (e.g. another adapted ore),
        // otherwise fall back to the position-independent collect.
        BlockState backdrop = BackdropSampler.sample(level, pos);
        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        if (backdropModel instanceof DynamicBlockStateModel dynamic) {
            dynamic.collectParts(level, pos, backdrop, random, parts);
        } else {
            backdropModel.collectParts(random, parts);
        }

        // Overlay: emit this ore's cutout fragments slightly outward along each face so they
        // don't z-fight with the backdrop layer directly beneath them. The offset is applied by
        // rebuilding the overlay's quad collection, leaving the shared baked overlay model
        // untouched.
        BlockStateModel overlayModel = OverlayModelRegistry.get(oreType);
        if (overlayModel != null) {
            List<BlockModelPart> overlayParts = new ArrayList<>();
            overlayModel.collectParts(random, overlayParts);
            for (BlockModelPart part : overlayParts) {
                parts.add(offset(part));
            }
        }
    }

    /** Rebuilds a baked part's quad collection offset outward along each face normal, forcing it
     * onto the translucent layer regardless of the overlay model's own declared layer (its
     * {@code cube_all} parent has no explicit render_type, so it would otherwise inherit a
     * non-translucent default - matching the same fix applied on Forge and the explicit
     * {@code ChunkSectionLayer.TRANSLUCENT} this replaced on mc/1.21.9's NeoForge port). */
    private static BlockModelPart offset(BlockModelPart part) {
        if (part instanceof SimpleModelWrapper simple) {
            QuadCollection offsetQuads = QuadHelper.offset(simple.quads(), OVERLAY_OFFSET);
            return new SimpleModelWrapper(offsetQuads, simple.useAmbientOcclusion(), simple.particleIcon(), ChunkSectionLayer.TRANSLUCENT);
        }
        return part;
    }
}
