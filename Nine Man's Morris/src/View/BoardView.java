package View;

import Logic.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public class BoardView {
    /**
     The BoardView class has three main properties: positions, edges, and boardScale. positions is an ArrayList
     that holds all the positions on the board. edges is a Map that associates each position with its neighboring
     positions. boardScale represents the scale of the game board.
     **/
    ArrayList<Position> positions = new ArrayList<>();

    Map<Position, ArrayList<Position>> edges = new HashMap<>();
    double boardScale;


    public void initBoard(double boardScale) {
        /**
         The initBoard method initializes the board by creating the positions and setting up the edges between
         them. It takes the boardScale as a parameter. The method uses the radius array to determine the positions'
         coordinates based on the board scale. It iterates over the radius values and creates the positions and
         their corresponding edges, adding them to the edges map.
         **/
        this.boardScale = boardScale;
        double[] radius = {1, 0.7, 0.4};

        Position prev_n0 = null;
        Position prev_0p = null;
        Position prev_p0 = null;
        Position prev_0n = null;

        for (var r : radius) {
            r = r * boardScale;
            Position p_nn = new Position(-r, -r);
            Position p_n0 = new Position(-r, 0);
            Position p_np = new Position(-r, r);
            edges.put(p_n0, new ArrayList<>(Arrays.asList(p_nn, p_np)));

            Position p_0p = new Position(0, r);
            edges.put(p_np, new ArrayList<>(Arrays.asList(p_n0, p_0p)));

            Position p_pp = new Position(r, r);
            edges.put(p_0p, new ArrayList<>(Arrays.asList(p_np, p_pp)));

            Position p_p0 = new Position(r, 0);
            edges.put(p_pp, new ArrayList<>(Arrays.asList(p_0p, p_p0)));

            Position p_pn = new Position(r, -r);
            edges.put(p_p0, new ArrayList<>(Arrays.asList(p_pp, p_pn)));

            Position p_0n = new Position(0, -r);
            edges.put(p_pn, new ArrayList<>(Arrays.asList(p_p0, p_0n)));

            edges.put(p_0n, new ArrayList<>(Arrays.asList(p_pn, p_nn)));
            edges.put(p_nn, new ArrayList<>(Arrays.asList(p_0n, p_n0)));

            if (prev_n0 != null) {
                edges.get(p_0n).add(prev_0n);
                edges.get(prev_0n).add(p_0n);

                edges.get(p_n0).add(prev_n0);
                edges.get(prev_n0).add(p_n0);

                edges.get(p_0p).add(prev_0p);
                edges.get(prev_0p).add(p_0p);

                edges.get(p_p0).add(prev_p0);
                edges.get(prev_p0).add(p_p0);
            }
            prev_n0 = p_n0;
            prev_p0 = p_p0;
            prev_0p = p_0p;
            prev_0n = p_0n;

            positions.add(p_nn);
            positions.add(p_n0);
            positions.add(p_np);
            positions.add(p_0p);
            positions.add(p_pp);
            positions.add(p_p0);
            positions.add(p_pn);
            positions.add(p_0n);

        }
    }

    public ArrayList<Position> getPositions() {
        return positions;
    }

    public Map<Position, ArrayList<Position>> getEdges() {
        return edges;
    }

    public void draw() {
        /**
         The draw method renders the game board. It sets the pen color to gray and fills a rectangle to represent
         the background of the board. Then, it iterates over the edges map and draws lines between each position
         and its neighbors. The lines are only drawn if the end position has coordinates less than the start
         position, ensuring that each line is drawn only once.
         **/
        StdDraw.setPenColor(StdDraw.GRAY);
        StdDraw.filledRectangle(0, 0, boardScale * 1.1, boardScale * 1.1);

        for (var edge : edges.entrySet()) {
            StdDraw.setPenColor(StdDraw.BLACK);
            StdDraw.setPenRadius(0.005);
            var start = edge.getKey();
            var neighbors = edge.getValue();
            for (var end : neighbors) {
                if (end.getX() < start.getX() || end.getY() < start.getY()) {
                    StdDraw.line(start.getX(), start.getY(), end.getX(), end.getY());
                }
            }

        }

    }
}
