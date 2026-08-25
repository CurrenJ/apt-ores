package grill24.aptores.fabric.client.model;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/** Vertex-manipulation helper for compositing baked quads from two independently-baked models. */
public final class QuadHelper {
    private QuadHelper() {
    }

    /**
     * Offsets a quad slightly outward along its face normal so the overlay layer doesn't
     * z-fight with the backdrop layer directly beneath it.
     */
    public static BakedQuad offsetQuad(BakedQuad quad, @Nullable Direction side, float offset) {
        Direction direction = side != null ? side : quad.direction();

        int[] originalVertices = quad.vertices();
        int[] newVertices = originalVertices.clone();

        float offsetX = 0, offsetY = 0, offsetZ = 0;
        if (direction != null) {
            offsetX = direction.getStepX() * offset;
            offsetY = direction.getStepY() * offset;
            offsetZ = direction.getStepZ() * offset;
        }

        int vertexSize = originalVertices.length / 4;
        if (vertexSize < 8) {
            return quad;
        }

        for (int vertex = 0; vertex < 4; vertex++) {
            int baseIndex = vertex * vertexSize;

            float x = Float.intBitsToFloat(newVertices[baseIndex]);
            float y = Float.intBitsToFloat(newVertices[baseIndex + 1]);
            float z = Float.intBitsToFloat(newVertices[baseIndex + 2]);

            x += offsetX;
            y += offsetY;
            z += offsetZ;

            newVertices[baseIndex] = Float.floatToRawIntBits(x);
            newVertices[baseIndex + 1] = Float.floatToRawIntBits(y);
            newVertices[baseIndex + 2] = Float.floatToRawIntBits(z);
        }

        return new BakedQuad(newVertices, quad.tintIndex(), quad.direction(), quad.sprite(), quad.shade(), quad.lightEmission());
    }
}
