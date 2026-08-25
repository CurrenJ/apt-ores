package grill24.aptores.neoforge.client.model;

import grill24.aptores.BackdropSampler;
import grill24.aptores.OreTypeDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Purely-visual composite: the backdrop layer is the live-sampled neighbor block's own block
 * state model parts, the overlay layer is a single, pre-baked {@link BlockModelPart} built from
 * this ore's cutout fragment texture (see {@code AptOresNeoForgeClient}). No world state is
 * written - {@link #collectParts(BlockAndTintGetter, BlockPos, BlockState, RandomSource, List)}
 * just samples the six neighbors fresh every mesh rebuild, the same way vanilla already
 * re-triggers a neighborhood remesh for AO/connection-dependent rendering, so this stays in sync
 * automatically when a neighbor is placed or broken.
 *
 * <p>{@code vanillaOreModel} (the model this instance replaces) is kept only as a fallback for
 * contexts that don't give us a position (e.g. the inventory/GUI collectParts overload) and as a
 * source of truth for the particle icon; its geometry is otherwise never emitted.
 */
public class AptOresModel implements BlockStateModel {
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
    public void collectParts(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos, @NotNull BlockState state,
                              @NotNull RandomSource rand, @NotNull List<BlockModelPart> parts) {
        BlockState backdrop = BackdropSampler.sample(level, pos);
        BlockStateModel backdropModel = getBackdropModel(backdrop);
        if (backdropModel != null) {
            backdropModel.collectParts(level, pos, backdrop, rand, parts);
        } else {
            vanillaOreModel.collectParts(rand, parts);
        }
        parts.add(overlayPart);
    }

    private BlockStateModel getBackdropModel(BlockState backdrop) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop);
    }

    @Override
    public @NotNull TextureAtlasSprite particleIcon() {
        return vanillaOreModel.particleIcon();
    }
}
