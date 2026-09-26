package io.github.hannnz1.morris.backend.service;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
public record GameCommittedEvent(GameResponse game) {}
