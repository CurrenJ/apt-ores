package grill24.aptores.fabric.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricBakedModelManager;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Purely-visual, stateless composite: renders the neighbor-sampled backdrop block's own
 * block-state model as the base layer, with this ore's cutout overlay on top. No world state is
 * written or read beyond a live lookup of the six neighboring block states every time this block
 * is (re)meshed - the same technique connected-texture mods use to pick a texture from neighbors.
 *
 * <p>1.21.5 replaced Fabric's old {@code BakedModel}/{@code FabricBakedModel} pair (mixed onto
 * every vanilla {@code BakedModel}, with {@code isVanillaAdapter()} guarding a no-op default -
 * see the "Fabric FabricBakedModel gotcha" in {@code docs/DEVELOPMENT.md}) with {@link
 * BlockStateModel} (vanilla's own type, shared with NeoForge/Forge post-rework) plus {@link
 * FabricBlockStateModel}, which the Fabric Renderer API mixes onto {@code BlockStateModel}
 * itself via an interface-injection mixin (not a per-implementation one) - so, unlike the old
 * gotcha, {@code instanceof FabricBlockStateModel} really is always true for every {@code
 * BlockStateModel} in the game, <em>and</em> its default {@code emitQuads(...)} is a real
 * implementation (delegates to {@link BlockStateModel#collectParts(RandomSource)} and encodes
 * each part through the emitter), not a stub - confirmed by disassembling the real
 * fabric-renderer-api-v1 6.1.2 class file, not assumed. There is no live-position overload of
 * plain {@code collectParts(...)} on Fabric the way NeoForge patches one directly onto vanilla's
 * {@code BlockStateModel} (see {@code neoforge/.../client/model/AptOresModel}); {@code
 * FabricBlockStateModel.emitQuads(..., BlockAndTintGetter, BlockPos, ...)} is Fabric's only
 * position-aware entry point, and every real chunk-mesh render goes through it.
 *
 * <p>The overlay model is pinned via {@code ExtraModelKey}/{@code SimpleUnbakedExtraModel} (see
 * {@code AptOresModelLoadingPlugin}) and looked up lazily through {@code
 * FabricBakedModelManager.getModel(...)} on first render rather than at bake time, for the same
 * reason the previous port's {@code OverlayModelRegistry} did a lazy lookup: nothing guarantees
 * our overlay's extra-model bake has completed by the time {@code modifyBlockModelAfterBake}
 * fires for an ore block in the same reload, but every bake is guaranteed done before anything
 * renders.
 *
 * <p>The wrapped {@code vanillaOreModel} is kept only as a source of truth for the particle icon
 * and as the position-blind {@link #collectParts} fallback's backdrop-less base; its own geometry
 * is otherwise never emitted.
 */
public class AptOresBakedModel implements BlockStateModel, FabricBlockStateModel {
    private static final float OVERLAY_OFFSET = 0.0025f;
    private static final RenderMaterial OVERLAY_MATERIAL = createOverlayMaterial();

    private final OreTypeDefinition oreType;
    private final BlockStateModel vanillaOreModel;
    private final ExtraModelKey<BlockStateModel> overlayKey;

    public AptOresBakedModel(OreTypeDefinition oreType, BlockStateModel vanillaOreModel,
                              ExtraModelKey<BlockStateModel> overlayKey) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
        this.overlayKey = overlayKey;
    }

    private static RenderMaterial createOverlayMaterial() {
        var renderer = Renderer.get();
        if (renderer == null) {
            return null;
        }
        return renderer.materialFinder()
            .blendMode(BlendMode.TRANSLUCENT)
            .disableDiffuse(true)
            .find();
    }

    @Nullable
    private BlockStateModel getOverlayModel() {
        return ((FabricBakedModelManager) Minecraft.getInstance().getModelManager()).getModel(overlayKey);
    }

    private BlockStateModel getBackdropModel(BlockState backdrop) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state,
                           RandomSource rand, Predicate<@Nullable Direction> cullTest) {
        BlockState backdrop = BackdropSampler.sample(blockView, pos);
        BlockStateModel backdropModel = getBackdropModel(backdrop);
        ((FabricBlockStateModel) backdropModel).emitQuads(emitter, blockView, pos, backdrop, rand, cullTest);

        BlockStateModel overlayModel = getOverlayModel();
        if (overlayModel != null && OVERLAY_MATERIAL != null) {
            emitOverlayQuads(emitter, overlayModel, rand);
        }
    }

    private void emitOverlayQuads(QuadEmitter emitter, BlockStateModel overlayModel, RandomSource rand) {
        for (BlockModelPart part : overlayModel.collectParts(rand)) {
            for (Direction direction : Direction.values()) {
                for (BakedQuad quad : part.getQuads(direction)) {
                    emitter.fromVanilla(QuadHelper.offsetQuad(quad, direction, OVERLAY_OFFSET), OVERLAY_MATERIAL, direction).emit();
                }
            }
            for (BakedQuad quad : part.getQuads(null)) {
                emitter.fromVanilla(QuadHelper.offsetQuad(quad, null, OVERLAY_OFFSET), OVERLAY_MATERIAL, quad.direction()).emit();
            }
        }
    }

    /**
     * Position-blind fallback for callers that bypass the Fabric Renderer API's {@code
     * emitQuads} entirely (e.g. some item-frame or GUI previews) - uses a fixed stone backdrop
     * since there's no world/position here, the same fallback the old {@code getQuads(...)} path
     * used.
     */
    @Override
    public void collectParts(@NotNull RandomSource rand, @NotNull List<BlockModelPart> parts) {
        BlockState defaultBackdrop = Blocks.STONE.defaultBlockState();
        BlockStateModel backdropModel = getBackdropModel(defaultBackdrop);
        if (backdropModel != null) {
            backdropModel.collectParts(rand, parts);
        } else {
            vanillaOreModel.collectParts(rand, parts);
        }

        BlockStateModel overlayModel = getOverlayModel();
        if (overlayModel != null) {
            parts.addAll(overlayModel.collectParts(rand));
        }
    }

    @Override
    public @NotNull TextureAtlasSprite particleIcon() {
        return vanillaOreModel.particleIcon();
    }
}
