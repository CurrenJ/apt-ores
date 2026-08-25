package grill24.aptores.fabric.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import grill24.aptores.fabric.client.OverlayModelRegistry;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModelPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Purely-visual, stateless composite: renders the neighbor-sampled backdrop block's own baked
 * block-state model as the base layer, with this ore's cutout overlay on top. No world state is
 * written or read beyond a live lookup of the six neighboring block states every time this block
 * is (re)meshed - the same technique connected-texture mods use to pick a texture from neighbors.
 *
 * <p>Rendering goes through the Fabric Renderer API ({@link FabricBlockStateModel#emitQuads}),
 * which the renderer uses for every {@link BlockStateModel}. The wrapped {@code vanillaOreModel}
 * is kept only as a source of truth for particles, break progress, and the vanilla fallback path
 * ({@code collectParts}); its quads are not emitted by default.
 */
public class AptOresBlockStateModel extends WrapperBlockStateModel {
    private static final float OVERLAY_OFFSET = 0.001f;

    private final OreTypeDefinition oreType;

    public AptOresBlockStateModel(OreTypeDefinition oreType, BlockStateModel vanillaOreModel) {
        super(vanillaOreModel);
        this.oreType = oreType;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos,
                          BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        BlockState backdrop = BackdropSampler.sample(blockView, pos, state);

        // Backdrop: delegate straight to the sampled neighbor block's own model. Every
        // BlockStateModel has FabricBlockStateModel mixed in at runtime, so this works for both
        // vanilla models (the default emitQuads collects and emits their baked parts) and models
        // from other Fabric renderer mods (their custom emission path).
        BlockStateModel backdropModel = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(backdrop);
        if (backdropModel != null) {
            ((FabricBlockStateModel) backdropModel).emitQuads(emitter, blockView, pos, backdrop, random, cullTest);
        }

        // Overlay: emit the ore's cutout fragments slightly outward along each face so they don't
        // z-fight with the backdrop layer directly beneath them. The QuadTransform applies the
        // offset at emission time without mutating the shared baked overlay model.
        BlockStateModel overlayModel = OverlayModelRegistry.get(oreType);
        if (overlayModel != null) {
            List<BlockStateModelPart> parts = new ArrayList<>();
            overlayModel.collectParts(random, parts);
            if (!parts.isEmpty()) {
                emitter.pushTransform(quad -> {
                    Direction face = quad.nominalFace();
                    if (face != null) {
                        quad.translate(
                            face.getStepX() * OVERLAY_OFFSET,
                            face.getStepY() * OVERLAY_OFFSET,
                            face.getStepZ() * OVERLAY_OFFSET);
                    }
                    return true;
                });
                for (BlockStateModelPart part : parts) {
                    ((FabricBlockStateModelPart) part).emitQuads(emitter, cullTest);
                }
                emitter.popTransform();
            }
        }
    }
}
