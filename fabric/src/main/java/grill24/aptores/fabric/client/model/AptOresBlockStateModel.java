package grill24.aptores.fabric.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.fabric.client.OverlayModelRegistry;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;

import java.util.function.Predicate;

/**
 * Purely-visual, stateless composite: renders the neighbor-sampled backdrop block's own baked
 * block-state model as the base layer, with this ore's cutout overlay on top. No world state is
 * written or read beyond a live lookup of the six neighboring block states every time this block
 * is (re)meshed - the same technique connected-texture mods use to pick a texture from neighbors.
 *
 * <p>Rendering goes through the Fabric Renderer API ({@link FabricBlockStateModel#emitQuads}),
 * which is mixed onto every vanilla {@link BlockStateModel}. The wrapped model (the vanilla ore
 * model this instance replaces) is kept only as a source of truth for particles and the vanilla
 * fallback path ({@code collectParts}); its quads are not emitted by default.
 */
public class AptOresBlockStateModel extends WrapperBlockStateModel {
    private static final float OVERLAY_OFFSET = 0.001f;

    private final OreTypeDefinition oreType;

    public AptOresBlockStateModel(OreTypeDefinition oreType, BlockStateModel wrapped) {
        super(wrapped);
        this.oreType = oreType;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos,
                           BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        // Backdrop: delegate straight to the sampled neighbor block's own model. Every
        // BlockStateModel has FabricBlockStateModel mixed in at runtime (its default emitQuads
        // correctly delegates to collectParts for plain vanilla models), so this works both for
        // vanilla models and for models from other Fabric renderer mods.
        BlockState backdrop = BackdropSampler.sample(blockView, pos);
        BlockStateModel backdropModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
        ((FabricBlockStateModel) backdropModel).emitQuads(emitter, blockView, pos, backdrop, random, cullTest);

        // Overlay: emit the ore's cutout fragments slightly outward along each face so they don't
        // z-fight with the backdrop layer directly beneath them. Delegated through the emitter's
        // own transform pipeline (pushTransform/popTransform) rather than manually rebuilding
        // BakedQuad objects and feeding them in "cold" via fromBakedQuad - the latter doesn't
        // reliably carry sprite/atlas UV binding the same way the native emitQuads path does,
        // which was producing badly distorted (stretched/wrong-sprite) overlay textures.
        BlockStateModel overlayModel = OverlayModelRegistry.get(oreType);
        if (overlayModel != null) {
            emitter.pushTransform(AptOresBlockStateModel::offsetAndMarkTranslucent);
            try {
                ((FabricBlockStateModel) overlayModel).emitQuads(emitter, blockView, pos, state, random, cullTest);
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
}
