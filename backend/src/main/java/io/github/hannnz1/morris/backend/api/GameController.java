package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.api.GameApiDtos.ActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.DrawActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameRequestForPlayer;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.JoinGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.JoinGameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.FieldError;
import io.github.hannnz1.morris.backend.service.GameSessionService;
import io.github.hannnz1.morris.backend.service.PlayerService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/games")
public class GameController {

    private final GameSessionService gameSessions;
    private final PlayerService players;
    private final Validator validator;

    public GameController(GameSessionService gameSessions, PlayerService players, Validator validator) {
        this.gameSessions = gameSessions;
        this.players = players;
        this.validator = validator;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateGameResponse create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                     // Bound as a raw JsonNode (not CreateGameRequest directly): the Bearer path's body
                                     // carries a different shape (CreateGameRequestForPlayer's timeControl) than the
                                     // legacy anonymous path's (CreateGameRequest's whitePlayer), and Spring only reads
                                     // the request body once, so both branches extract their own fields from the same
                                     // parsed JSON below.
                                     @RequestBody(required = false) JsonNode body) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Idempotency-Key is required");
            }
            CreateGameRequestForPlayer request = new CreateGameRequestForPlayer(textOrNull(body, "timeControl"));
            return gameSessions.createForPlayer(players.requirePlayer(authorization.substring(7)), idempotencyKey,
                    request.timeControl());
        }
        // No Authorization header: preserve the pre-M1 anonymous create flow for in-flight legacy
        // clients. @Valid can't be declared on the shared request parameter above (an empty {}
        // Bearer-path body would then fail validation before the Bearer branch is even reached),
        // so this branch replicates Spring's own @Valid/MethodArgumentNotValidException handling
        // (same VALIDATION_FAILED code and fieldErrors shape) by validating manually.
        CreateGameRequest legacyRequest = new CreateGameRequest(textOrNull(body, "whitePlayer"));
        var violations = validator.validate(legacyRequest);
        if (!violations.isEmpty()) {
            List<FieldError> fieldErrors = violations.stream()
                    .map(this::toFieldError)
                    .toList();
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid", fieldErrors);
        }
        return gameSessions.create(legacyRequest);
    }

    private String textOrNull(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        return body.get(field).asText();
    }

    private FieldError toFieldError(ConstraintViolation<?> violation) {
        String field = violation.getPropertyPath().toString();
        return new FieldError(field, violation.getMessage());
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
                                 @RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                 @RequestBody(required = false) JoinGameRequest legacyRequest) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Idempotency-Key is required");
            }
            return gameSessions.joinByBearer(id, players.requirePlayer(authorization.substring(7)), idempotencyKey);
        }
        // No Authorization header: preserve the pre-M1 anonymous join flow (see the matching
        // comment in create()) by validating the legacy body manually instead of via @Valid.
        if (legacyRequest == null) {
            legacyRequest = new JoinGameRequest(null, null);
        }
        var violations = validator.validate(legacyRequest);
        if (!violations.isEmpty()) {
            List<FieldError> fieldErrors = violations.stream()
                    .map(this::toFieldError)
                    .toList();
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid", fieldErrors);
        }
        return gameSessions.join(id, legacyRequest);
    }

    @PostMapping("/{id}/actions")
    public GameResponse action(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Player-Token", required = false) String legacyToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ActionRequest request) {
        var outcome = gameSessions.performAction(id, bearerToken(authorization), legacyToken, idempotencyKey, request);
        // Per spec M2.3, the service already committed the TIMEOUT verdict inside its own
        // transaction (it never throws to signal this); only here, after that commit, do we turn a
        // clock-timeout rejection into an HTTP 409. The finished game itself still reaches the
        // client via the WebSocket broadcast GameFinisher.finish triggers.
        if (outcome.rejectedByTimeout()) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_ACTIVE", "This player's time expired");
        }
        return outcome.game();
    }

    @PostMapping("/{id}/resign")
    public GameResponse resign(@PathVariable("id") UUID id,
                               @RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return gameSessions.resign(id, bearerToken(authorization), idempotencyKey);
    }

    @PostMapping("/{id}/cancel")
    public GameResponse cancel(@PathVariable("id") UUID id,
                               @RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return gameSessions.cancel(id, bearerToken(authorization), idempotencyKey);
    }

    @PostMapping("/{id}/draw")
    public GameResponse draw(@PathVariable("id") UUID id,
                             @RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                             @Valid @RequestBody DrawActionRequest request) {
        return gameSessions.offerDraw(id, bearerToken(authorization), idempotencyKey, request.action());
    }

    @PostMapping("/{id}/rematch")
    public GameResponse rematch(@PathVariable("id") UUID id,
                                @RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                @Valid @RequestBody DrawActionRequest request) {
        return gameSessions.offerRematch(id, bearerToken(authorization), idempotencyKey, request.action());
    }

    private String bearerToken(String authorizationHeader) {
        return (authorizationHeader != null && authorizationHeader.startsWith("Bearer "))
                ? authorizationHeader.substring("Bearer ".length())
                : null;
    }
}
