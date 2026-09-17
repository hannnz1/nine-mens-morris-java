package Manager;

import Logic.Position;
import java.util.Set;

/** Per-game UI projection; rules belong exclusively to game-engine. */
public class CandidateMgr {
    private Set<Position> candidates = Set.of();
    public Set<Position> getCandidates() { return candidates; }
    public void setCandidates(Set<Position> candidates) { this.candidates = Set.copyOf(candidates); }
}
