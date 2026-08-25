package grill24.aptores.fabric.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.QuadHelper;
import grill24.aptores.fabric.client.OverlayModelRegistry;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
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
        // z-fight with the backdrop layer directly beneath them.
        BlockStateModel overlayModel = OverlayModelRegistry.get(oreType);
        if (overlayModel != null) {
            List<BlockModelPart> parts = overlayModel.collectParts(random);
            for (BlockModelPart part : parts) {
                if (part instanceof SimpleModelWrapper simple) {
                    emitOffsetQuads(emitter, simple.quads(), cullTest);
                }
            }
        }
    }

    private static void emitOffsetQuads(QuadEmitter emitter, QuadCollection quads, Predicate<Direction> cullTest) {
        QuadCollection offset = QuadHelper.offset(quads, OVERLAY_OFFSET);

        for (Direction direction : Direction.values()) {
            if (cullTest.test(direction)) {
                continue;
            }
            for (BakedQuad quad : offset.getQuads(direction)) {
                emitter.fromBakedQuad(quad).cullFace(direction).nominalFace(direction).emit();
            }
        }
        for (BakedQuad quad : offset.getQuads(null)) {
            emitter.fromBakedQuad(quad).emit();
        }
    }
}
