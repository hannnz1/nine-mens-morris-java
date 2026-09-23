package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.api.GameApiDtos.ActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.JoinGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.JoinGameResponse;
import io.github.hannnz1.morris.backend.service.GameSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/games")
public class GameController {

    private final GameSessionService gameSessions;

    public GameController(GameSessionService gameSessions) {
        this.gameSessions = gameSessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateGameResponse create(@Valid @RequestBody CreateGameRequest request) {
        return gameSessions.create(request);
    }

    @GetMapping("/{id}")
    public GameResponse get(@PathVariable("id") UUID id) {
        return gameSessions.get(id);
    }

    @GetMapping("/{id}/session")
    public GameResponse restore(@PathVariable("id") UUID id,
                                @RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestHeader(value = "X-Player-Token", required = false) String legacyToken) {
        return gameSessions.restore(id, bearerToken(authorization), legacyToken);
    }

    @PostMapping("/{id}/join")
    public JoinGameResponse join(@PathVariable("id") UUID id,
                                 @Valid @RequestBody JoinGameRequest request) {
        return gameSessions.join(id, request);
    }

    @PostMapping("/{id}/actions")
    public GameResponse action(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Player-Token", required = false) String legacyToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ActionRequest request) {
        return gameSessions.performAction(id, bearerToken(authorization), legacyToken, idempotencyKey, request);
    }

    private String bearerToken(String authorizationHeader) {
        return (authorizationHeader != null && authorizationHeader.startsWith("Bearer "))
                ? authorizationHeader.substring("Bearer ".length())
                : null;
    }
}
