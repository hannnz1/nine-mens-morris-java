package io.github.hannnz1.morris.backend.service;
import io.github.hannnz1.morris.engine.Player;
import io.github.hannnz1.morris.engine.ai.Difficulty;
import java.util.*;
/** Fixed identities from V4; never authenticated through public player credentials. */
public final class BotRoster {
    private BotRoster() {}
    public static final List<UUID> IDS = List.of(
            UUID.fromString("00000000-0000-4000-8000-00000000b001"),
            UUID.fromString("00000000-0000-4000-8000-00000000b002"),
            UUID.fromString("00000000-0000-4000-8000-00000000b003"));
    public static UUID id(Difficulty difficulty) { return IDS.get(difficulty.ordinal()); }
    public static Difficulty difficulty(UUID id) {
        if (id == null) return null;
        int index = IDS.indexOf(id);
        return index < 0 ? null : Difficulty.values()[index];
    }
    public static boolean isBot(UUID id) { return id != null && IDS.contains(id); }
    public static Player side(UUID white, UUID black) {
        return isBot(white) ? Player.WHITE : isBot(black) ? Player.BLACK : null;
    }
    public static Difficulty difficulty(UUID white, UUID black) {
        return difficulty(isBot(white) ? white : black);
    }
}
