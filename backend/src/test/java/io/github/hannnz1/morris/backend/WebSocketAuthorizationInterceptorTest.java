package io.github.hannnz1.morris.backend;

import io.github.hannnz1.morris.backend.config.WebSocketAuthorizationInterceptor;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketAuthorizationInterceptorTest {

    @Test
    void requiresAPlayerTokenForGameSubscriptions() {
        UUID gameId = UUID.randomUUID();
        TokenService tokens = new TokenService();
        GameSessionRepository games = mock(GameSessionRepository.class);
        GameSessionEntity game = new GameSessionEntity(gameId, "Alice", "Bob",
                tokens.hash("white-secret"), tokens.hash("black-secret"),
                "IN_PROGRESS", "{}", Instant.now());
        when(games.findById(gameId)).thenReturn(Optional.of(game));
        WebSocketAuthorizationInterceptor interceptor =
                new WebSocketAuthorizationInterceptor(games, tokens);
        MessageChannel channel = mock(MessageChannel.class);

        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(subscription(gameId, null), channel));
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(subscription(gameId, "wrong"), channel));
        assertDoesNotThrow(
                () -> interceptor.preSend(subscription(gameId, "white-secret"), channel));
        assertDoesNotThrow(
                () -> interceptor.preSend(subscription(gameId, "black-secret"), channel));
    }

    private Message<byte[]> subscription(UUID gameId, String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/games/" + gameId);
        if (token != null) {
            accessor.setNativeHeader("X-Player-Token", token);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
