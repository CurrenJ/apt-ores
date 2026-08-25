package grill24.aptores.forge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Purely-visual composite: the backdrop layer is the live-sampled neighbor block's own block
 * state model parts, the overlay layer is a single, pre-baked {@link BlockModelPart} built from
 * this ore's cutout fragment texture (see {@code AptOresForgeClient}). No world state is written -
 * {@link #getModelData} just samples the six neighbors fresh every mesh rebuild, the same way
 * vanilla already re-triggers a neighborhood remesh for AO/connection-dependent rendering, so
 * this stays in sync automatically when a neighbor is placed or broken.
 *
 * <p>Unlike NeoForge (which dropped {@code ModelData} from block rendering entirely in 1.21.5 in
 * favor of passing {@code level}/{@code pos} directly into {@code collectParts}), regular
 * (Minecraft)Forge kept the {@code ModelData} mechanism nearly unchanged from 1.21.4 - only the
 * baked-geometry types it carries changed (from {@code BakedModel}/quads to
 * {@code BlockStateModel}/{@link BlockModelPart}). {@code vanillaOreModel} (the model this
 * instance replaces) is kept only as a fallback for the position/data-unaware
 * {@link #collectParts(RandomSource, List)} overload and as a source of truth for the particle
 * icon; its geometry is otherwise never emitted.
 */
public class AptOresModel implements BlockStateModel {
    public static final ModelProperty<BlockState> BACKDROP_PROPERTY = new ModelProperty<>();

    private static final ChunkRenderTypeSet OVERLAY_TYPES = ChunkRenderTypeSet.of(RenderType.translucent());

    private final OreTypeDefinition oreType;
    private final BlockStateModel vanillaOreModel;
    private final BlockModelPart overlayPart;

    public AptOresModel(OreTypeDefinition oreType, BlockStateModel vanillaOreModel, BlockModelPart overlayPart) {
        this.oreType = oreType;
        this.vanillaOreModel = vanillaOreModel;
        this.overlayPart = overlayPart;
    }

    @Override
    public void collectParts(@NotNull RandomSource rand, @NotNull List<BlockModelPart> parts) {
        vanillaOreModel.collectParts(rand, parts);
        parts.add(overlayPart);
    }

    @Override
    public void collectParts(@NotNull RandomSource rand, @NotNull List<BlockModelPart> parts,
                              @NotNull ModelData data, @Nullable RenderType renderType) {
        BlockState backdrop = data.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        BlockStateModel backdropModel = getBackdropModel(backdrop);
        if (backdropModel != null) {
            backdropModel.collectParts(rand, parts, ModelData.EMPTY, renderType);
        } else {
            vanillaOreModel.collectParts(rand, parts);
        }

        if (renderType == null || OVERLAY_TYPES.contains(renderType)) {
            parts.add(overlayPart);
        }
    }

    private BlockStateModel getBackdropModel(BlockState backdrop) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos,
                                            @NotNull BlockState state, @NotNull ModelData modelData) {
        BlockState backdrop = BackdropSampler.sample(level, pos);
        return modelData.derive().with(BACKDROP_PROPERTY, backdrop).build();
    }

    @Override
    public @NotNull TextureAtlasSprite particleIcon() {
        return vanillaOreModel.particleIcon();
    }

    @Override
    public @NotNull TextureAtlasSprite particleIcon(@NotNull ModelData data) {
        return vanillaOreModel.particleIcon();
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        BlockState backdrop = data.get(BACKDROP_PROPERTY);
        if (backdrop == null) {
            backdrop = BackdropSampler.DEFAULT_BACKDROP;
        }

        BlockStateModel backdropModel = getBackdropModel(backdrop);
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
}
