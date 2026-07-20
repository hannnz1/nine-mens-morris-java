package io.github.hannnz1.morris.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BoardTopology {

    private static final Map<BoardPosition, Set<BoardPosition>> NEIGHBOURS = buildNeighbours();
    private static final List<Set<BoardPosition>> MILLS = buildMills();

    private BoardTopology() {
    }

    public static Set<BoardPosition> neighboursOf(BoardPosition position) {
        requirePosition(position);
        return NEIGHBOURS.get(position);
    }

    public static List<Set<BoardPosition>> mills() {
        return MILLS;
    }

    public static boolean areAdjacent(BoardPosition first, BoardPosition second) {
        requirePosition(first);
        requirePosition(second);
        return NEIGHBOURS.get(first).contains(second);
    }

    private static Map<BoardPosition, Set<BoardPosition>> buildNeighbours() {
        EnumMap<BoardPosition, Set<BoardPosition>> graph = new EnumMap<>(BoardPosition.class);
        for (BoardPosition position : BoardPosition.values()) {
            graph.put(position, EnumSet.noneOf(BoardPosition.class));
        }

        BoardPosition[][] rings = {
                {BoardPosition.A1, BoardPosition.D1, BoardPosition.G1, BoardPosition.G4,
                        BoardPosition.G7, BoardPosition.D7, BoardPosition.A7, BoardPosition.A4},
                {BoardPosition.B2, BoardPosition.D2, BoardPosition.F2, BoardPosition.F4,
                        BoardPosition.F6, BoardPosition.D6, BoardPosition.B6, BoardPosition.B4},
                {BoardPosition.C3, BoardPosition.D3, BoardPosition.E3, BoardPosition.E4,
                        BoardPosition.E5, BoardPosition.D5, BoardPosition.C5, BoardPosition.C4}
        };

        for (BoardPosition[] ring : rings) {
            for (int index = 0; index < ring.length; index++) {
                connect(graph, ring[index], ring[(index + 1) % ring.length]);
            }
        }

        connect(graph, BoardPosition.D1, BoardPosition.D2);
        connect(graph, BoardPosition.D2, BoardPosition.D3);
        connect(graph, BoardPosition.G4, BoardPosition.F4);
        connect(graph, BoardPosition.F4, BoardPosition.E4);
        connect(graph, BoardPosition.D7, BoardPosition.D6);
        connect(graph, BoardPosition.D6, BoardPosition.D5);
        connect(graph, BoardPosition.A4, BoardPosition.B4);
        connect(graph, BoardPosition.B4, BoardPosition.C4);

        EnumMap<BoardPosition, Set<BoardPosition>> immutable = new EnumMap<>(BoardPosition.class);
        graph.forEach((position, neighbours) -> immutable.put(position, Collections.unmodifiableSet(neighbours)));
        return Collections.unmodifiableMap(immutable);
    }

    private static List<Set<BoardPosition>> buildMills() {
        List<Set<BoardPosition>> mills = new ArrayList<>();
        addRingMills(mills, BoardPosition.A1, BoardPosition.D1, BoardPosition.G1, BoardPosition.G4,
                BoardPosition.G7, BoardPosition.D7, BoardPosition.A7, BoardPosition.A4);
        addRingMills(mills, BoardPosition.B2, BoardPosition.D2, BoardPosition.F2, BoardPosition.F4,
                BoardPosition.F6, BoardPosition.D6, BoardPosition.B6, BoardPosition.B4);
        addRingMills(mills, BoardPosition.C3, BoardPosition.D3, BoardPosition.E3, BoardPosition.E4,
                BoardPosition.E5, BoardPosition.D5, BoardPosition.C5, BoardPosition.C4);

        mills.add(immutableMill(BoardPosition.D1, BoardPosition.D2, BoardPosition.D3));
        mills.add(immutableMill(BoardPosition.G4, BoardPosition.F4, BoardPosition.E4));
        mills.add(immutableMill(BoardPosition.D7, BoardPosition.D6, BoardPosition.D5));
        mills.add(immutableMill(BoardPosition.A4, BoardPosition.B4, BoardPosition.C4));
        return Collections.unmodifiableList(mills);
    }

    private static void addRingMills(List<Set<BoardPosition>> mills, BoardPosition... ring) {
        mills.add(immutableMill(ring[0], ring[1], ring[2]));
        mills.add(immutableMill(ring[2], ring[3], ring[4]));
        mills.add(immutableMill(ring[4], ring[5], ring[6]));
        mills.add(immutableMill(ring[6], ring[7], ring[0]));
    }

    private static Set<BoardPosition> immutableMill(BoardPosition first, BoardPosition second,
                                                     BoardPosition third) {
        return Collections.unmodifiableSet(EnumSet.of(first, second, third));
    }

    private static void connect(Map<BoardPosition, Set<BoardPosition>> graph,
                                BoardPosition first, BoardPosition second) {
        graph.get(first).add(second);
        graph.get(second).add(first);
    }

    private static void requirePosition(BoardPosition position) {
        if (position == null) {
            throw new IllegalArgumentException("Board position is required");
        }
    }
}

