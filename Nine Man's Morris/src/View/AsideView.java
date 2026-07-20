package View;
import Logic.*;
import Manager.*;

import java.awt.*;

//This class provides side view, unplaced tokens and removed tokens for both players.
public class AsideView {
    // Reference to the game object
    private Game game;

    // Scale of the game board
    private double boardScale;

    // Indicator variables for animation
    private boolean isShowingIndicator = false;

    private double t;

    // Constructor
    public AsideView(Game game, double boardScale) {
        this.game = game;
        this.boardScale = boardScale;
    }

    // Update method to handle animation
    public void update(double deltaTime) {
        t += deltaTime;
        if (t < 0.2) {
            isShowingIndicator = true;
        } else if (t < 0.4) {
            isShowingIndicator = false;
        } else {
            t = 0;
        }
    }

    // Draw method to render the aside view
    public void draw() {
        StdDraw.setPenColor(StdDraw.RED);
        Font font = new Font("Arial", Font.BOLD, 15);
        StdDraw.setFont(font);


        // Draw white aside view
        if (game.isWhiteRound() ) {
            if (isShowingIndicator) {
                StdDraw.textLeft(boardScale * -1.53, 0, "" + game.getWhiteAside() + " <");
            } else {
                StdDraw.textLeft(boardScale * -1.53, 0, "" + game.getWhiteAside() + "");
            }
        } else {
            StdDraw.textLeft(boardScale * -1.53, 0, "" + game.getWhiteAside() + "");
        }
        StdDraw.textLeft(boardScale * -1.53, 0.1, "out:" + game.getWhiteOut());

        // Draw black aside view
        if (game.isWhiteRound()) {
            StdDraw.textRight(boardScale * 1.53, 0, "" + game.getBlackAside());
        } else {
            if (isShowingIndicator) {
                StdDraw.textRight(boardScale * 1.53, 0, "> " + game.getBlackAside());
            } else {
                StdDraw.textRight(boardScale * 1.53, 0, "" + game.getBlackAside());
            }
        }
        StdDraw.textRight(boardScale * 1.53, 0.1, "out:" + game.getBlackOut());
        // Draw the tokens
        drawToken();

    }

    // Helper method to draw the tokens in the aside view
    private void drawToken() {
        // Draw background panels
        // white unplaced
        double panelLen = boardScale * 0.7;
        double len = panelLen * 2;
        double innerLen = len * 0.8;
        double start = (len - innerLen) / 2 - panelLen;
        double distance = innerLen / 8;
        double offsetScale = 1.25;
        StdDraw.setPenColor(StdDraw.PRINCETON_ORANGE);
        double tokenSize = 0.04;
        StdDraw.filledRectangle(0, boardScale * -offsetScale, panelLen, 0.04);
        // Draw white unplaced tokens
        int i = 0;
        for (; i < game.getWhiteAside(); i ++) {
            StdDraw.setPenColor(StdDraw.WHITE);
            StdDraw.setPenRadius(tokenSize);
            StdDraw.point(start + distance * i, boardScale * -offsetScale);
        }
        // Draw empty slots for remaining white unplaced tokens
        for (; i < 9; i ++) {
            StdDraw.setPenColor(StdDraw.GRAY);
            StdDraw.setPenRadius(tokenSize);
            StdDraw.point(start + distance * i, boardScale * -offsetScale);
        }
        // Draw labels
        StdDraw.setPenColor(StdDraw.BLACK);
        Font font = new Font("Arial", Font.BOLD, 15);
        StdDraw.setFont(font);
        StdDraw.text(0, boardScale * -1.35, "Remaining tokens");
        StdDraw.text(0, boardScale * 1.35, "Remaining tokens");
        StdDraw.text(boardScale * -1.35, boardScale * -0.8, "Removed tokens");
        StdDraw.text(boardScale * 1.35, boardScale * -0.8, "Removed tokens");


        // Draw black unplaced tokens
        StdDraw.setPenColor(StdDraw.PRINCETON_ORANGE);
        StdDraw.filledRectangle(0, boardScale * offsetScale, panelLen, 0.04);

        i = 0;
        for (; i < game.getBlackAside(); i ++) {
            StdDraw.setPenColor(StdDraw.BLACK);
            StdDraw.setPenRadius(tokenSize);
            StdDraw.point(start + distance * i, boardScale * offsetScale);
        }

        for (; i < 9; i ++) {
            StdDraw.setPenColor(StdDraw.GRAY);
            StdDraw.setPenRadius(tokenSize);
            StdDraw.point(start + distance * i, boardScale * offsetScale);
        }

        // Draw white kicked out tokens
        StdDraw.setPenColor(StdDraw.PRINCETON_ORANGE);
        StdDraw.filledRectangle(boardScale * -offsetScale, 0, 0.04, panelLen);

        i = 0;
        for (; i < game.getWhiteOut(); i ++) {
            StdDraw.setPenColor(StdDraw.WHITE);
            StdDraw.setPenRadius(tokenSize);
            StdDraw.point(boardScale * -offsetScale, start + distance * i);
        }

        for (; i < 9; i ++) {
            StdDraw.setPenColor(StdDraw.GRAY);
            StdDraw.setPenRadius(tokenSize);
            StdDraw.point(boardScale * -offsetScale, start + distance * i);
        }

        // Draw black kicked out tokens
        StdDraw.setPenColor(StdDraw.PRINCETON_ORANGE);
        StdDraw.filledRectangle(boardScale * offsetScale, 0, 0.04, panelLen);

        i = 0;
        for (; i < game.getBlackOut(); i ++) {
            StdDraw.setPenColor(StdDraw.BLACK);
            StdDraw.setPenRadius(tokenSize);
            StdDraw.point(boardScale * offsetScale, start + distance * i);
        }

        for (; i < 9; i ++) {
            StdDraw.setPenColor(StdDraw.GRAY);
            StdDraw.setPenRadius(tokenSize);
            StdDraw.point(boardScale * offsetScale, start + distance * i);
        }
    }
}
