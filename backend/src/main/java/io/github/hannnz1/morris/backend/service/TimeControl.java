package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;

public record TimeControl(int baseMs, int incrementMs) {
    public static final TimeControl DEFAULT = new TimeControl(5 * 60_000, 3_000);

    private static final Map<String, TimeControl> SUPPORTED = Map.of(
            "3+2", new TimeControl(3 * 60_000, 2_000),
            "5+3", DEFAULT,
            "10+5", new TimeControl(10 * 60_000, 5_000)
    );

    // The display label for stored base/increment millis (e.g. 300_000 / 3_000 -> "5+3"), or
    // null when the game has no time control.
    public static String label(Integer baseMs, Integer incrementMs) {
        if (baseMs == null || incrementMs == null) {
            return null;
        }
        return (baseMs / 60_000) + "+" + (incrementMs / 1_000);
    }

    public static TimeControl parse(String label) {
        if (label == null) {
            return DEFAULT;
        }
        TimeControl parsed = SUPPORTED.get(label);
        if (parsed == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "timeControl must be one of 3+2, 5+3, 10+5");
        }
        return parsed;
    }
}
