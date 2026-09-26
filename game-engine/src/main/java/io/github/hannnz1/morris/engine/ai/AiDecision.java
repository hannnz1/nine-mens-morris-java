package io.github.hannnz1.morris.engine.ai;
import io.github.hannnz1.morris.engine.GameAction;
public record AiDecision(GameAction action, boolean fallbackUsed, int completedDepth) {}
