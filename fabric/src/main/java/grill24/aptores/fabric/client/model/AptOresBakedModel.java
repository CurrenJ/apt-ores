package grill24.aptores.fabric.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.fabric.client.OverlayModelRegistry;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Purely-visual, stateless composite: renders the neighbor-sampled backdrop block's own baked
 * model as the base layer, with this ore's cutout overlay on top. No world state is written or
 * read beyond a live lookup of the six neighboring block states every time this block is
 * (re)meshed - the same technique connected-texture mods use to pick a texture from neighbors.
 *
 * <p>The wrapped {@code vanillaOreModel} is kept only as a source of truth for the particle icon
 * (so break particles still look correct); its geometry is never emitted. Item/inventory
 * rendering for these blocks is unaffected by this class - as of 1.21.6 it's baked completely
 * separately (see {@code BlockModelWrapper}/{@code ItemModel}), so the composite look is only
 * visible in-world.
 */
public class AptOresBakedModel implements BlockStateModel, FabricBlockStateModel {
    private static final float OVERLAY_OFFSET = 0.0025f;

    private static final net.fabricmc.fabric.api.renderer.v1.mesh.QuadTransform OVERLAY_TRANSFORM = quad -> {
        Direction direction = quad.lightFace();
        float dx = direction.getStepX() * OVERLAY_OFFSET;
        float dy = direction.getStepY() * OVERLAY_OFFSET;
        float dz = direction.getStepZ() * OVERLAY_OFFSET;
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.pos(vertex, quad.x(vertex) + dx, quad.y(vertex) + dy, quad.z(vertex) + dz);
        }
        quad.renderLayer(ChunkSectionLayer.TRANSLUCENT);
        return true;
    };

    private final OreTypeDefinition oreType;
    private final BlockStateModel vanillaOreModel;

    public AptOresBakedModel(OreTypeDefinition oreType, BlockStateModel vanillaOreModel) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
    }

    private BlockStateModel getOverlayModel() {
        return OverlayModelRegistry.get(oreType);
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state,
                           RandomSource random, Predicate<@Nullable Direction> cullTest) {
        BlockState backdrop = BackdropSampler.sample(blockView, pos);

        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        ((FabricBlockStateModel) backdropModel).emitQuads(emitter, blockView, pos, backdrop, random, cullTest);

        BlockStateModel overlayModel = getOverlayModel();
        if (overlayModel != null) {
            emitter.pushTransform(OVERLAY_TRANSFORM);
            try {
                ((FabricBlockStateModel) overlayModel).emitQuads(emitter, blockView, pos, state, random, cullTest);
            } finally {
                emitter.popTransform();
            }
        }
    }

    @Override
    public @Nullable Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random) {
        // Depends on live neighbor state - never cache.
        return null;
    }

    @Override
    public TextureAtlasSprite particleSprite(BlockAndTintGetter blockView, BlockPos pos, BlockState state) {
        return vanillaOreModel.particleIcon();
    }

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> parts) {
        // Fallback path for callers that bypass the Fabric Renderer API and query this model
        // with no world/position context. Falls back to the vanilla-model geometry (no neighbor
        // sampling is possible here).
        vanillaOreModel.collectParts(random, parts);
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return vanillaOreModel.particleIcon();
    }
}
