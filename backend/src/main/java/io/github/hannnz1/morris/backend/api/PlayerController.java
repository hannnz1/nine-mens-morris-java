package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.PlayerResponse;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.RenamePlayerRequest;
import io.github.hannnz1.morris.backend.service.PlayerService;
import io.github.hannnz1.morris.backend.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/players")
public class PlayerController {

    private final PlayerService playerService;
    private final RateLimiter rateLimiter;

    public PlayerController(PlayerService playerService, RateLimiter rateLimiter) {
        this.playerService = playerService;
        this.rateLimiter = rateLimiter;
    }

    // server.forward-headers-strategy: native (application.yml) makes Spring's ForwardedHeaderFilter
    // rewrite getRemoteAddr() from X-Forwarded-For only for requests it treats as coming through a
    // proxy. This deployment's Caddy sits directly in front of the app on the same private network,
    // so no additional trusted-proxy allowlist is configured here.
    @PostMapping
    public PlayerResponse create(@Valid @RequestBody CreatePlayerRequest request, HttpServletRequest httpRequest) {
        String key = "player-create:" + httpRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquire(key, 10, Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many player creations from this address");
        }
        return playerService.createOrGet(request);
    }

    @GetMapping("/me")
    public PlayerResponse me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return playerService.getByToken(bearerToken(authorization));
    }

    @PatchMapping("/me")
    public PlayerResponse rename(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @Valid @RequestBody RenamePlayerRequest request) {
        return playerService.rename(bearerToken(authorization), request.nickname());
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "A Bearer authorization header is required");
        }
        return authorizationHeader.substring("Bearer ".length());
    }
}
