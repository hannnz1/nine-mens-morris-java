package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.api.GameApiDtos.RoomLookupResponse;
import io.github.hannnz1.morris.backend.service.GameSessionService;
import io.github.hannnz1.morris.backend.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final GameSessionService gameSessions;
    private final RateLimiter rateLimiter;

    public RoomController(GameSessionService gameSessions, RateLimiter rateLimiter) {
        this.gameSessions = gameSessions;
        this.rateLimiter = rateLimiter;
    }

    // Same forward-headers-strategy note as PlayerController.create(): getRemoteAddr() is already
    // rewritten from X-Forwarded-For for proxied requests, no extra trusted-proxy allowlist needed.
    @GetMapping("/{roomCode}")
    public RoomLookupResponse lookup(@PathVariable("roomCode") String roomCode, HttpServletRequest httpRequest) {
        if (!roomCode.matches("\\d{6}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Room code must be 6 digits");
        }
        // 30/min per IP, per spec §3.5 - the room code space is only 1e6, so this endpoint is
        // rate-limited to make brute-force enumeration impractical. See M1 final review I2.
        String key = "room-lookup:" + httpRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquire(key, 30, Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many room lookups from this address");
        }
        return gameSessions.lookupRoom(roomCode);
    }
}
