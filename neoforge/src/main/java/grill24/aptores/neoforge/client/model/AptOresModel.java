package grill24.aptores.neoforge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Purely-visual composite: the backdrop layer is the live-sampled neighbor block's own baked
 * model, the overlay layer is this ore's cutout fragment texture. No world state is written -
 * {@link #getModelData} just samples the six neighbors fresh every mesh rebuild, the same way
 * vanilla already re-triggers a neighborhood remesh for AO/connection-dependent rendering, so
 * this stays in sync automatically when a neighbor is placed or broken.
 *
 * <p>{@code vanillaOreModel} (the model this instance replaces) is kept only as a source of
 * truth for particle icon and item transforms; its geometry is never emitted.
 */
public class AptOresModel implements BakedModel {
    public static final ModelProperty<BlockState> BACKDROP_PROPERTY = new ModelProperty<>();

    private static final float OVERLAY_OFFSET = 0.001f;
    private static final ChunkRenderTypeSet OVERLAY_TYPES = ChunkRenderTypeSet.of(RenderType.translucent());

    private final OreTypeDefinition oreType;
    private final BakedModel vanillaOreModel;
    private final BakedModel overlayModel;

    public AptOresModel(OreTypeDefinition oreType, BakedModel vanillaOreModel, BakedModel overlayModel) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
        this.overlayModel = overlayModel;
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
        return getQuads(state, side, rand, ModelData.EMPTY, null);
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                              @NotNull RandomSource rand, @NotNull ModelData modelData,
                                              @Nullable RenderType renderType) {
        List<BakedQuad> quads = new ArrayList<>();

        BlockState backdrop = modelData.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        BakedModel backdropModel = getBackdropModel(backdrop);
        if (backdropModel != null) {
            boolean shouldRenderBackdrop = renderType == null;
            if (!shouldRenderBackdrop) {
                try {
                    shouldRenderBackdrop = backdropModel.getRenderTypes(backdrop, rand, ModelData.EMPTY).contains(renderType);
                } catch (Exception e) {
                    shouldRenderBackdrop = renderType == RenderType.solid();
                }
            }
            if (shouldRenderBackdrop) {
                quads.addAll(backdropModel.getQuads(backdrop, side, rand, ModelData.EMPTY, renderType));
            }
        }

        if (renderType == null || OVERLAY_TYPES.contains(renderType)) {
            for (BakedQuad quad : overlayModel.getQuads(state, side, rand, ModelData.EMPTY, renderType)) {
                quads.add(QuadHelper.offsetQuad(quad, side, OVERLAY_OFFSET));
            }
        }

        return quads;
    }

    private BakedModel getBackdropModel(BlockState backdrop) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos,
                                            @NotNull BlockState state, @NotNull ModelData modelData) {
        BlockState backdrop = BackdropSampler.sample(level, pos);
        return modelData.derive().with(BACKDROP_PROPERTY, backdrop).build();
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return vanillaOreModel.getParticleIcon();
    }

    @Override
    public TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
        return vanillaOreModel.getParticleIcon(data);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        BlockState backdrop = data.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        BakedModel backdropModel = getBackdropModel(backdrop);
        ChunkRenderTypeSet backdropTypes = ChunkRenderTypeSet.of(RenderType.solid());
        if (backdropModel != null) {
            try {
                backdropTypes = backdropModel.getRenderTypes(backdrop, rand, ModelData.EMPTY);
            } catch (Exception ignored) {
                // Keep the SOLID fallback above.
            }
        }

        return ChunkRenderTypeSet.union(backdropTypes, OVERLAY_TYPES);
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ItemTransforms getTransforms() {
        return vanillaOreModel.getTransforms();
    }
}
