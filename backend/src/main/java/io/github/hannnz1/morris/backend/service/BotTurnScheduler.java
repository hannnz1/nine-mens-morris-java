package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.*;
import io.github.hannnz1.morris.engine.ai.*;
import org.springframework.context.event.EventListener;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class BotTurnScheduler implements AutoCloseable {
    @FunctionalInterface public interface Delay { void schedule(Runnable task,long millis); }
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(BotTurnScheduler.class);
    private final BotGameAccess games;
    private final MorrisAi ai;
    private final Delay delay;
    private final Executor computation;
    private final BooleanSupplier ready;
    private final Duration budget;
    private final int minDelay,maxDelay;
    private final Set<UUID> inFlight=ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID,Integer> failures=new ConcurrentHashMap<>();
    private volatile boolean closed;
    public BotTurnScheduler(BotGameAccess games,MorrisAi ai,Delay delay,Executor computation,
                            BooleanSupplier ready,Duration budget,int minDelay,int maxDelay) {
        if(minDelay<0||maxDelay<minDelay||maxDelay==Integer.MAX_VALUE||budget.isNegative()||budget.isZero())
            throw new IllegalArgumentException("Invalid bot timing configuration");
        this.games=games;this.ai=ai;this.delay=delay;this.computation=computation;this.ready=ready;
        this.budget=budget;this.minDelay=minDelay;this.maxDelay=maxDelay;
    }
    @EventListener public void committed(GameCommittedEvent event) { onGameCommitted(event.game()); }
    public void onGameCommitted(GameResponse game) {
        if(!"IN_PROGRESS".equals(game.status())) {
            synchronized(failures) { failures.remove(game.id()); }
        }
        if(eligible(game)) schedule(game.id(),false);
    }
    static boolean eligible(GameResponse game) {
        return "IN_PROGRESS".equals(game.status()) && game.botSide()!=null && game.state().currentPlayer()==game.botSide();
    }
    public boolean isInFlight(UUID id) { return inFlight.contains(id); }
    public void schedule(UUID id,boolean skipDelay) {
        if(closed||!ready.getAsBoolean()||!inFlight.add(id))return;
        try {
            long millis=skipDelay?0:ThreadLocalRandom.current().nextInt(minDelay,maxDelay+1);
            delay.schedule(()->{
                try { long queuedAt=System.nanoTime(); if(closed) inFlight.remove(id); else computation.execute(()->run(id,queuedAt)); }
                catch(RuntimeException rejected) { inFlight.remove(id); LOG.warn("Bot queue rejected game {}",id,rejected); }
            },millis);
        } catch(RuntimeException rejected) { inFlight.remove(id); LOG.warn("Bot delay rejected game {}",id,rejected); }
    }
    private void run(UUID id,long queuedAt) {
        boolean successful=false;
        double queueMs=(System.nanoTime()-queuedAt)/1_000_000.0;
        try {
            if(closed||!ready.getAsBoolean()) return;
            for(int i=0;i<3;i++) {
                var game=games.get(id);
                if(!eligible(game)) { if(!"IN_PROGRESS".equals(game.status())) failures.remove(id); return; }
                long seed=id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(),17) ^ game.version();
                long searchStarted=System.nanoTime();
                var action=failures.getOrDefault(id,0)>=3 ? new RandomMover(seed).chooseAction(game.state())
                        : ai.chooseAction(game.state(),game.botDifficulty(),seed,budget);
                LOG.debug("BOT_TIMING game={} queueMs={} searchMs={}",id,queueMs,(System.nanoTime()-searchStarted)/1_000_000.0);
                if(closed||Thread.currentThread().isInterrupted())return;
                var result=games.act(id,BotRoster.id(game.botDifficulty()),
                        new ActionRequest(action.type(),action.from(),action.to(),game.version()));
                failures.remove(id);successful=true;
                if(result.rejectedByTimeout()||!eligible(result.game())) break;
            }
        } catch(ApiException expected) {
            if(Set.of("GAME_NOT_ACTIVE","VERSION_CONFLICT","NOT_YOUR_TURN","GAME_NOT_FOUND").contains(expected.code()))
                LOG.debug("Bot game {} no longer needs this task: {}",id,expected.code());
            else failed(id,expected);
        } catch(RuntimeException error) { failed(id,error); }
        finally {
            inFlight.remove(id);
            // A human can reply before the old task removes inFlight; read again after releasing it.
            if(successful&&!closed) {
                try { if(eligible(games.get(id))) schedule(id,false); }
                catch(RuntimeException error) { LOG.debug("Bot follow-up will be retried by watchdog for {}",id,error); }
            }
        }
    }
    private void failed(UUID id,RuntimeException error) {
        // Serialize failure insertion with terminal notifications and close. Re-read inside
        // this boundary: a computation may have started before the game finished.
        synchronized(failures) {
            if(closed)return;
            try {
                if(!"IN_PROGRESS".equals(games.get(id).status())) { failures.remove(id); return; }
            } catch(RuntimeException lookupError) {
                LOG.warn("Could not verify game {} after bot failure; watchdog will retry",id,lookupError);
                return;
            }
            int attempt=failures.merge(id,1,Integer::sum);
            LOG.error("Bot computation failed for game {} (attempt {})",id,attempt,error);
        }
    }
    @Override public void close() {
        synchronized(failures) { closed=true;inFlight.clear();failures.clear(); }
    }
}
