package io.github.hannnz1.morris.backend.service;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
@Component
@ConditionalOnProperty(name="morris.scheduling.enabled",havingValue="true",matchIfMissing=true)
public class BotWatchdog {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(BotWatchdog.class);
    private final GameSessionRepository games;
    private final BotGameAccess access;
    private final BotTurnScheduler turns;
    private final Clock clock;
    private final DowntimeCompensator compensator;
    private final int limit;
    public BotWatchdog(GameSessionRepository games,BotGameAccess access,BotTurnScheduler turns,Clock clock,
                       DowntimeCompensator compensator,@Value("${morris.bot.max-active-games:20}") int limit) {
        this.games=games;this.access=access;this.turns=turns;this.clock=clock;this.compensator=compensator;this.limit=limit;
    }
    @Scheduled(fixedDelay=2000) public void scanOnce() {
        if(!compensator.ready())return;
        for(var id:games.findStalledBotGames(BotRoster.IDS,clock.instant().minusSeconds(3),Limit.of(limit))) {
            try { if(!turns.isInFlight(id)&&BotTurnScheduler.eligible(access.get(id)))turns.schedule(id,true); }
            catch(RuntimeException error) { LOG.warn("Bot watchdog could not retry {}",id,error); }
        }
    }
}
