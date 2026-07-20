package View;

import Logic.Game;
import Logic.PositionStatus;

public class PointPainter {


    private Game v;

    public PointPainter(Game view) {
        this.v = view;
    }

    public void update(double time_step) {

    }

    /**
     The PointPainter class has an update method which can be used for updating any logic related to the
     point painting process . The draw method iterates over the formedLines and calls their respective
     draw method to draw the lines. Then, it iterates over the positions and, based on the PositionStatus,
     uses StdDraw to draw points with different colors and sizes.
     **/
    void draw() {
        int index = 0;

        for (var line : v.formedLines) {
            line.draw();
        }

        for (var start : v.positions) {
            // draw itself
            if (start.getStatus() == PositionStatus.EMPTY) {
                StdDraw.setPenColor(StdDraw.BLACK);
                StdDraw.setPenRadius(0.026);
                StdDraw.point(start.getX(), start.getY());
            } else if(start.getStatus() == PositionStatus.BLACK) {
                StdDraw.setPenColor(StdDraw.BLACK);
                StdDraw.setPenRadius(0.09);
                StdDraw.point(start.getX(), start.getY());
            } else {
                StdDraw.setPenColor(StdDraw.WHITE);
                StdDraw.setPenRadius(0.09);
                StdDraw.point(start.getX(), start.getY());
            }

        }


    }
}
