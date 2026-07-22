package io.github.hannnz1.morris.backend.config;

import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.service.TokenService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WebSocketAuthorizationInterceptor implements ChannelInterceptor {

    private static final String TOPIC_PREFIX = "/topic/games/";

    private final GameSessionRepository games;
    private final TokenService tokens;

    public WebSocketAuthorizationInterceptor(GameSessionRepository games, TokenService tokens) {
        this.games = games;
        this.tokens = tokens;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
        if (accessor == null || accessor.getMessageType() != SimpMessageType.SUBSCRIBE) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
            throw new IllegalArgumentException("Subscriptions are only allowed for game topics");
        }

        UUID gameId;
        try {
            gameId = UUID.fromString(destination.substring(TOPIC_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The game subscription destination is invalid", exception);
        }

        String token = accessor.getFirstNativeHeader("X-Player-Token");
        var game = games.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("The game does not exist"));
        boolean authorized = token != null && (tokens.matches(token, game.getWhiteTokenHash())
                || (!game.getBlackTokenHash().isEmpty()
                && tokens.matches(token, game.getBlackTokenHash())));
        if (!authorized) {
            throw new IllegalArgumentException("A valid player token is required to subscribe");
        }
        return message;
    }
}
