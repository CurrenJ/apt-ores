package grill24.aptores;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Picks the "backdrop" material an ore should render as, purely by looking at what's currently
 * touching it. No state is stored or synced anywhere - this is a stateless function of the
 * live world, called fresh every time the containing chunk section is (re)meshed. This mirrors
 * how connected-texture mods (e.g. Continuity) pick a texture variant from neighbor state at
 * mesh time, and it means the visual result is always in sync: break/place a neighbor block and
 * the ore's appearance updates the same tick, the same way glass-pane connections do.
 */
public final class BackdropSampler {
    public static final BlockState DEFAULT_BACKDROP = Blocks.STONE.defaultBlockState();

    private BackdropSampler() {
    }

    public static BlockState sample(BlockGetter level, BlockPos origin) {
        Map<BlockState, Integer> counts = new HashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.values()) {
            cursor.setWithOffset(origin, direction);
            BlockState neighbor = level.getBlockState(cursor);
            if (isValidBackdrop(level, cursor, neighbor)) {
                // Weight non-stone/deepslate backdrops higher so a single deliberately-placed
                // block (e.g. blackstone) wins over the plain stone the ore usually sits in.
                int weight = (neighbor.is(Blocks.STONE) || neighbor.is(Blocks.DEEPSLATE)) ? 1 : 4;
                counts.merge(neighbor, weight, Integer::sum);
            }
        }

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElseGet(() -> defaultBackdropFor(level.getBlockState(origin)));
    }

    /**
     * With no eligible neighbor, fall back to whichever of stone/deepslate matches the ore's own
     * variant rather than always stone - a deepslate ore surrounded only by non-solid or
     * ore-family blocks should still read as deepslate.
     */
    private static BlockState defaultBackdropFor(BlockState oreState) {
        return defaultBackdropFor(BuiltInRegistries.BLOCK.getKey(oreState.getBlock()));
    }

    /**
     * Same stone/deepslate choice as {@link #defaultBackdropFor(BlockState)}, from a block or
     * block-model id rather than a live {@link BlockState} - used for the position-less item/GUI
     * icon, where there's no world or neighbor to sample.
     */
    public static BlockState defaultBackdropFor(ResourceLocation oreOrModelId) {
        return oreOrModelId.getPath().contains("deepslate") ? Blocks.DEEPSLATE.defaultBlockState() : DEFAULT_BACKDROP;
    }

    private static boolean isValidBackdrop(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.canOcclude()) {
            return false;
        }
        // canOcclude() is a static per-block flag, not a check of the actual shape - blocks like
        // snow layers don't disable it even though they usually don't fill the block space. Only
        // treat a neighbor as a backdrop if it actually occupies the full cube, otherwise partial
        // blocks (snow layers, slabs, carpets, stairs, etc.) get sampled as if they were a solid
        // wall and the ore renders wearing that block's texture, which looks wrong.
        if (!state.isCollisionShapeFullBlock(level, pos)) {
            return false;
        }
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        // Don't let one ore's backdrop be "another ore" - that would render nonsensically since
        // the neighbor is itself being intercepted and rendered as a composite.
        return !OreTypeRegistry.isAdaptedOreBlockId(blockId);
    }
}
