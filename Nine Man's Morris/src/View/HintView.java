package View;
import Logic.*;
import Manager.*;

import java.awt.*;

public class HintView {
    private Game v;
    private double t;
    private boolean isAnimShowPrompt = false;


    private boolean isHint = false;

    public HintView(Game view) {
        this.v = view;
    }

    public void update(double time_step) {
        /**
         The update method is responsible for updating the hint view. It increments the t value by the time_step
         parameter and determines whether to show the prompt animation or not based on the value of t. The animation
         is toggled between the isAnimShowPrompt flag.
         */

        t += time_step;
        if (t < 0.22) {
            isAnimShowPrompt = true;
        } else if (t < 0.44) {
            isAnimShowPrompt = false;
        } else {
            t = 0;
        }
    }

    public void draw() {
        /**
         The draw method is responsible for rendering the hint view. It first determines the current side based on
         whether it is white's turn or black's turn. If the prompt animation is active and the hint is not enabled,
         it iterates over the candidate positions (obtained from CandidateMgr.getInstance().getCandidates()) and draws
         circles around them. The color of the circles depends on the status of the positions (empty or occupied). If
         there is a selected position (v.selectedPos is not null), it also draws a circle around the selected position
         in yellow.

         After drawing the hint circles, it sets the pen color to black and sets the font for the text. It then displays
         the prompt text on the left or right side of the screen, depending on the current side.
         */

        PositionStatus side = v.isWhiteRound()? PositionStatus.WHITE: PositionStatus.BLACK;


        if (isAnimShowPrompt && !isHint) {
            for (var start : CandidateMgr.getInstance().getCandidates()) {
                double radius = 0.05;
                Color c = StdDraw.GREEN;
                boolean needDraw = false;
                if (start.getStatus() == PositionStatus.EMPTY) {
                    radius = 0.02;
                }
                needDraw = true;
                if (needDraw) {
                    StdDraw.setPenRadius(0.0046);
                    StdDraw.setPenColor(c);
                    StdDraw.circle(start.getX(), start.getY(), radius);
                }
            }
            if (null != v.selectedPos) {
                double radius = 0.05;
                Color c = StdDraw.YELLOW;

                StdDraw.setPenRadius(0.0046);
                StdDraw.setPenColor(c);
                StdDraw.circle(v.selectedPos.getX(), v.selectedPos.getY(), radius);
            }


        }


        StdDraw.setPenColor(StdDraw.BLACK);
        Font font = new Font("Arial", Font.PLAIN, 18);
        StdDraw.setFont(font);
        if (v.isWhiteRound()) {
            StdDraw.textLeft(-0.9, -0.95,  v.getSide() + v.opPrompt);
        } else {
            StdDraw.textRight(0.9, -0.95, v.getSide() + v.opPrompt);
        }
    }

    public boolean isHint() {
        return isHint;
    }

    public void setHint(boolean hint) {
        isHint = hint;
    }

}
