package io.github.hannnz1.morris.backend.config;
import io.github.hannnz1.morris.backend.service.*;
import io.github.hannnz1.morris.engine.ai.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.time.Duration;
import java.util.concurrent.*;
@Configuration
@ConditionalOnProperty(name="morris.scheduling.enabled",havingValue="true",matchIfMissing=true)
public class BotConfig {
    @Bean(destroyMethod="shutdownNow") public ScheduledExecutorService botDelayExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"morris-bot-delay");t.setDaemon(true);return t;});
    }
    @Bean(destroyMethod="shutdownNow") public ThreadPoolExecutor botComputationExecutor(@Value("${morris.bot.threads:1}") int threads) {
        if(threads<1)throw new IllegalArgumentException("Bot threads must be positive");
        return new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(200),
                r->{var t=new Thread(r,"morris-bot-search");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean public BotTurnScheduler botTurnScheduler(BotGameAccess access,DowntimeCompensator compensator,
            ScheduledExecutorService botDelayExecutor,ThreadPoolExecutor botComputationExecutor,
            @Value("${morris.bot.hard-budget-ms:800}") long budget,
            @Value("${morris.bot.think-delay-min-ms:400}") int min,
            @Value("${morris.bot.think-delay-max-ms:800}") int max) {
        return new BotTurnScheduler(access,new AlphaBetaMorrisAi(),
                (task,ms)->botDelayExecutor.schedule(task,ms,TimeUnit.MILLISECONDS),botComputationExecutor,
                compensator::ready,Duration.ofMillis(budget),min,max);
    }
}
