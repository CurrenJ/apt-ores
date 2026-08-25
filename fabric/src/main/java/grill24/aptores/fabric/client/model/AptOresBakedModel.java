package grill24.aptores.fabric.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.fabric.client.OverlayModelRegistry;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricBakedModelManager;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.util.List;
import java.util.function.Predicate;

/**
 * Purely-visual, stateless composite: renders the neighbor-sampled backdrop block's own baked
 * model as the base layer, with this ore's cutout overlay on top. No world state is written or
 * read beyond a live lookup of the six neighboring block states every time this block is
 * (re)meshed - the same technique connected-texture mods use to pick a texture from neighbors.
 *
 * <p>Minecraft's 1.21.9 model rework replaced {@code BakedModel}/{@code FabricBakedModel} for
 * block-in-world rendering with {@link BlockStateModel}/{@code FabricBlockStateModel} (the latter
 * is mixed directly onto the {@link BlockStateModel} interface itself, so unlike the old
 * {@code FabricBakedModel} gotcha - see {@code docs/DEVELOPMENT.md} - every {@link BlockStateModel}
 * (including plain vanilla ones) can be called through {@link #emitQuads} directly with no
 * {@code isVanillaAdapter()}-style guard needed).
 *
 * <p>The wrapped {@code vanillaOreModel} is kept only as a source of truth for the particle icon
 * (so break particles still look correct); its geometry is never emitted.
 */
public class AptOresBakedModel implements BlockStateModel {
    private static final float OVERLAY_OFFSET = 0.0025f;

    private final OreTypeDefinition oreType;
    private final BlockStateModel vanillaOreModel;

    public AptOresBakedModel(OreTypeDefinition oreType, BlockStateModel vanillaOreModel) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
    }

    private @Nullable BlockStateModel getOverlayModel() {
        ExtraModelKey<BlockStateModel> key = OverlayModelRegistry.key(oreType);
        if (key == null) {
            return null;
        }
        return ((FabricBakedModelManager) Minecraft.getInstance().getModelManager()).getModel(key);
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state,
                           RandomSource random, Predicate<@Nullable Direction> cullTest) {
        BlockState backdrop = BackdropSampler.sample(blockView, pos);
        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        backdropModel.emitQuads(emitter, blockView, pos, backdrop, random, cullTest);

        BlockStateModel overlayModel = getOverlayModel();
        if (overlayModel != null) {
            emitter.pushTransform(AptOresBakedModel::offsetAndMarkTranslucent);
            try {
                overlayModel.emitQuads(emitter, blockView, pos, state, random, cullTest);
            } finally {
                emitter.popTransform();
            }
        }
    }

    private static boolean offsetAndMarkTranslucent(MutableQuadView quad) {
        Vector3fc normal = quad.faceNormal();
        for (int i = 0; i < 4; i++) {
            quad.pos(i,
                quad.x(i) + normal.x() * OVERLAY_OFFSET,
                quad.y(i) + normal.y() * OVERLAY_OFFSET,
                quad.z(i) + normal.z() * OVERLAY_OFFSET);
        }
        quad.renderLayer(ChunkSectionLayer.TRANSLUCENT);
        return true;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> parts) {
        // Deprecated, context-free fallback (no world/position available, e.g. some GUI
        // previews) - use a fixed stone backdrop, same as item/inventory rendering. Real
        // block-in-world rendering always goes through emitQuads above instead.
        BlockState backdrop = Blocks.STONE.defaultBlockState();
        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        backdropModel.collectParts(random, parts);
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return vanillaOreModel.particleIcon();
    }
}
