package grill24.aptores.forge.client.model;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A {@link BlockStateModel} that re-emits a pre-baked {@link QuadCollection} as a single part.
 *
 * <p>MC 26.1 changed the item-model pipeline: a {@code "minecraft:model"} item definition whose
 * {@code model} points at a plain block model now bakes into a
 * {@link net.minecraft.client.renderer.item.CuboidItemModelWrapper} (a quad collection baked with
 * an identity transform) instead of the 1.21.4-era {@code BlockStateModelWrapper}. Forge has no
 * equivalent of NeoForge's {@code ModelEvent.RegisterStandalone}, so this is how the shadow-item
 * trick (see {@link grill24.aptores.forge.client.AptOresForgeClient}) keeps working: we unwrap the
 * cuboid's quads and present them as the ore's overlay {@link BlockStateModel}. The overlay is
 * purely additive - emitted on top of the neighbor-sampled backdrop - so the top-level
 * {@link grill24.aptores.forge.client.model.AptOresModel} owns the particle material; this model
 * reports {@code null} for it.
 */
public final class BakedQuadBlockStateModel implements BlockStateModel {
    private final QuadCollection quads;

    public BakedQuadBlockStateModel(QuadCollection quads) {
        this.quads = quads;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        parts.add(new Part());
    }

    @Override
    public Material.Baked particleMaterial() {
        return null; // overlay is additive; the top-level model owns particle material
    }

    @Override
    public int materialFlags() {
        return quads.materialFlags();
    }

    private final class Part implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(@Nullable Direction direction) {
            return quads.getQuads(direction);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public Material.Baked particleMaterial() {
            return null; // overlay is additive; the top-level model owns particle material
        }

        @Override
        public int materialFlags() {
            return quads.materialFlags();
        }
    }
}
