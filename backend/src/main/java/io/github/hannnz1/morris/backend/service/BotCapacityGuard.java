package io.github.hannnz1.morris.backend.service;
import io.github.hannnz1.morris.backend.persistence.*;
import io.github.hannnz1.morris.backend.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
@Component
public class BotCapacityGuard {
    private final PlayerRepository players;
    private final GameSessionRepository games;
    private final int maximum;
    public BotCapacityGuard(PlayerRepository players, GameSessionRepository games,
                            @Value("${morris.bot.max-active-games:20}") int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Bot capacity must be positive");
        this.players=players; this.games=games; this.maximum=maximum;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() { players.findByIdForUpdate(BotRoster.IDS.get(0)).orElseThrow(); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAvailable() {
        lock();
        if (games.countBotGames(BotRoster.IDS) >= maximum)
            throw new ApiException(HttpStatus.CONFLICT,"BOT_BUSY","Computer opponents are busy; try again later");
    }
}
