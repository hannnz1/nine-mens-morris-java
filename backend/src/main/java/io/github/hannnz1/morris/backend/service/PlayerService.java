package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.PlayerResponse;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class PlayerService {

    private static final int MAX_NICKNAME_LENGTH = 16;
    private static final Duration LAST_SEEN_UPDATE_THRESHOLD = Duration.ofMinutes(5);

    private final PlayerRepository players;
    private final TokenService tokens;
    private final Set<String> blockedNicknames;

    public PlayerService(PlayerRepository players, TokenService tokens) {
        this.players = players;
        this.tokens = tokens;
        this.blockedNicknames = loadBlockedNicknames();
    }

    @Transactional
    public PlayerResponse createOrGet(CreatePlayerRequest request) {
        String nickname = validateNickname(request.nickname());
        String tokenHash = tokens.hash(request.clientToken());

        return players.findByTokenHash(tokenHash)
                .map(this::toResponse)
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    PlayerEntity saved = players.saveAndFlush(
                            new PlayerEntity(UUID.randomUUID(), nickname, tokenHash, "HUMAN", now, now));
                    return toResponse(saved);
                });
    }

    @Transactional(readOnly = true)
    public PlayerResponse getByToken(String bearerToken) {
        return toResponse(requirePlayer(bearerToken));
    }

    @Transactional
    public PlayerResponse rename(String bearerToken, String newNickname) {
        PlayerEntity player = requirePlayer(bearerToken);
        player.renameTo(validateNickname(newNickname));
        return toResponse(player);
    }

    @Transactional
    public PlayerEntity requirePlayer(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "A Bearer player token is required");
        }
        PlayerEntity player = players.findByTokenHash(tokens.hash(bearerToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Unknown player token"));
        Instant now = Instant.now();
        if (Duration.between(player.getLastSeenAt(), now).compareTo(LAST_SEEN_UPDATE_THRESHOLD) > 0) {
            player.touchLastSeen(now);
        }
        return player;
    }

    private String validateNickname(String rawNickname) {
        String nickname = rawNickname == null ? "" : rawNickname.strip();
        if (nickname.isEmpty() || nickname.length() > MAX_NICKNAME_LENGTH || nickname.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Nickname must be 1-16 characters with no control characters");
        }
        if (blockedNicknames.contains(nickname.toLowerCase(java.util.Locale.ROOT))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NICKNAME_NOT_ALLOWED",
                    "This nickname is not allowed");
        }
        return nickname;
    }

    private PlayerResponse toResponse(PlayerEntity player) {
        return new PlayerResponse(player.getId(), player.getNickname(), player.getCreatedAt());
    }

    private Set<String> loadBlockedNicknames() {
        try {
            return Set.copyOf(Files.readAllLines(
                    new ClassPathResource("blocked-nicknames.txt").getFile().toPath(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not load blocked-nicknames.txt", exception);
        }
    }
}
