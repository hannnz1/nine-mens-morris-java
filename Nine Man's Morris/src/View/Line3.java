package View;

import Logic.Position;
import Logic.PositionStatus;

import java.util.ArrayList;

public class Line3 {

    /**
     The Line3 class contains methods to check if a line is connected (isConnectedLine) and if it is formed
     by a specific PositionStatus (formedOneLine). The toString method returns a string representation of
     the line by concatenating the string representations of its points. The contains method checks if a
     given Position is present in the line. The draw method uses StdDraw to draw the line by iterating over
     the points and drawing lines between consecutive points.
     **/
    ArrayList<Position> points;

    public Line3(ArrayList<Position> points) {
        this.points = points;
    }

    @Override
    public String toString() {
        //the toString method returns a string representation of the line by connecting the points on the line.
        String ret = "";
        for (var e : points) {
            ret += ("" + e + "-");
        }

        return ret;

    }

    public boolean contains(Position p) {
        //the contains method checks if a given Position exists in the line.
        for (var a : points) {
            if (a.equals(p)) {
                return true;
            }
        }

        return false;
    }


    public boolean isConnectedLine() {
        PositionStatus sta = points.get(0).getStatus();

        if (sta == PositionStatus.EMPTY) {
            return false;
        }

        for (var p : points) {
            if (p.getStatus() != sta) {
                return false;
            }
        }

        return true;
    }

    public boolean formedOneLine(PositionStatus sta) {
        for (var p : points) {
            if (p.getStatus() != sta) {
                return false;
            }
        }

        return true;
    }

    public void draw() {
        for (int i = 0; i < points.size() -1; i ++) {
            Position start = points.get(i);
            Position end = points.get(i + 1);
            StdDraw.setPenRadius(0.016);
            StdDraw.setPenColor(StdDraw.PRINCETON_ORANGE);
            StdDraw.line(start.getX(), start.getY(), end.getX(), end.getY());
        }
    }
}
