package io.github.hannnz1.morris.backend.service;
import io.github.hannnz1.morris.backend.api.GameApiDtos.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.UUID;
/** Explicit new transactions also make synchronous after-commit test executors safe. */
@Component
public class BotGameAccess {
    private final GameSessionService games;
    public BotGameAccess(GameSessionService games) { this.games=games; }
    @Transactional(propagation=Propagation.REQUIRES_NEW, readOnly=true)
    public GameResponse get(UUID id) { return games.get(id); }
    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
    public ActionOutcome act(UUID id, UUID bot, ActionRequest request) { return games.performBotAction(id,bot,request); }
}
