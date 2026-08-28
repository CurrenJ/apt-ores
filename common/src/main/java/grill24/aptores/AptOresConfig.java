package grill24.aptores;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional client-side config at {@code config/aptores.json}. Lets players restrict which blocks
 * are eligible ore neighbors, on top of whatever {@link BackdropSampler} would otherwise accept.
 * Re-read on every client resource reload (see the per-loader model-baking hooks), so edits apply
 * with a resource pack reload (F3+T) - no restart required.
 *
 * <p>Absent, empty, or unparsable config = no restriction (every backdrop {@link BackdropSampler}
 * would otherwise accept stays eligible), so this is purely opt-in.
 */
public final class AptOresConfig {
    private static final String FILE_NAME = "aptores.json";
    private static final String KEY = "blockWhitelist";

    private static Set<Identifier> whitelistBlockIds = Set.of();
    private static List<TagKey<Block>> whitelistTags = List.of();
    private static boolean whitelistEnabled = false;

    private AptOresConfig() {
    }

    public static void load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            whitelistEnabled = false;
            whitelistBlockIds = Set.of();
            whitelistTags = List.of();
            return;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject json = GsonHelper.parse(reader);
            parseWhitelist(json);
        } catch (IOException | RuntimeException e) {
            AptOres.LOGGER.error("Apt Ores: failed to load config {}", file, e);
            whitelistEnabled = false;
            whitelistBlockIds = Set.of();
            whitelistTags = List.of();
        }
    }

    private static void parseWhitelist(JsonObject json) {
        if (!json.has(KEY)) {
            whitelistEnabled = false;
            whitelistBlockIds = Set.of();
            whitelistTags = List.of();
            return;
        }

        List<String> entries = new ArrayList<>();
        JsonElement element = json.get(KEY);
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                entries.add(array.get(i).getAsString());
            }
        } else if (element.isJsonPrimitive()) {
            // A single block id/tag is also accepted, so players don't need to wrap it in an array.
            entries.add(element.getAsString());
        }

        Set<Identifier> ids = new HashSet<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String entry : entries) {
            if (entry.isBlank()) {
                continue;
            }
            if (entry.startsWith("#")) {
                tags.add(TagKey.create(Registries.BLOCK, Identifier.parse(entry.substring(1))));
            } else {
                ids.add(Identifier.parse(entry));
            }
        }

        whitelistBlockIds = Set.copyOf(ids);
        whitelistTags = List.copyOf(tags);
        whitelistEnabled = !whitelistBlockIds.isEmpty() || !whitelistTags.isEmpty();

        if (whitelistEnabled) {
            AptOres.LOGGER.info("Apt Ores: loaded block whitelist ({} block id(s), {} tag(s))",
                whitelistBlockIds.size(), whitelistTags.size());
        }
    }

    /**
     * Whether {@code state} is allowed as an ore neighbor by the configured whitelist. Always true
     * when no whitelist is configured.
     */
    public static boolean isWhitelisted(BlockState state) {
        if (!whitelistEnabled) {
            return true;
        }

        for (TagKey<Block> tag : whitelistTags) {
            if (state.is(tag)) {
                return true;
            }
        }

        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return whitelistBlockIds.contains(blockId);
    }
}
