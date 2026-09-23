package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.api.GameApiDtos.RoomLookupResponse;
import io.github.hannnz1.morris.backend.service.GameSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final GameSessionService gameSessions;

    public RoomController(GameSessionService gameSessions) {
        this.gameSessions = gameSessions;
    }

    @GetMapping("/{roomCode}")
    public RoomLookupResponse lookup(@PathVariable("roomCode") String roomCode) {
        if (!roomCode.matches("\\d{6}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Room code must be 6 digits");
        }
        return gameSessions.lookupRoom(roomCode);
    }
}
