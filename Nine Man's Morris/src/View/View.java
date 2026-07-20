package View;
import Manager.*;
import Logic.*;

import java.awt.*;
import java.util.List;

//View.View class, creates the boardView, and then initialises the game mechanism inside the boardView with edges and positions.
//Also checks the mouse updates.
public class View {

    double boardScale = 0.64;

    Position curClickPosition = null;
    boolean isPrevTickPressed = false;


    Game game = null;

    AsideView aside;

    PointPainter posPainter;

    BoardView boardView;

    HintView hintView;

    HintButton hintButton;

    TutorialView tutorView;

    TutorialButton tutorButton;

    /**
     The View class initializes and manages several components of the game interface, including AsideView,
     PointPainter, BoardView, HintView, HintButton, TutorialView, and TutorialButton. It also creates an
     instance of the Game class and sets up the game board.
     */
    public View(){
        boardView = new BoardView();
        boardView.initBoard(boardScale);

        game = new Game(boardView.getPositions(), boardView.getEdges());
        game.debugInit();


        aside = new AsideView(game, boardScale);
        posPainter = new PointPainter(game);
        hintView = new HintView(game);
        hintButton = new HintButton(hintView);
        tutorView = new TutorialView(game);
        tutorButton = new TutorialButton(tutorView);

    }

    public boolean gameOver() {
        return game.gameOver();
    }


    /**
     The update method is responsible for updating the game state and all the associated components. It
     calls the update methods of the various components, including game, aside, posPainter, hintView, hintButton,
     HintManager, tutorView, and tutorButton. It also calls the mouseClickUpdate method to handle mouse click
     events.
     */
    public void update(double time_step) {


        game.update(time_step);
        aside.update(time_step);
        posPainter.update(time_step);
        hintView.update(time_step);
        hintButton.update(time_step);
        HintManager.getInstance().update(time_step);
        tutorView.update(time_step);
        tutorButton.update(time_step);
        mouseClickUpdate();
    }

    private Position getIntersectPos(double x, double y, List<Position> positionList) {
        for (var pos : positionList) {
            if (Math.pow(x - pos.getX(), 2) + Math.pow(y - pos.getY(), 2) < Math.pow(pos.getClickRadius(), 2))
            {
                return pos;
            }
        }

        return null;
    }

    private void mouseClickUpdate() {
        if (StdDraw.isMousePressed()) {
            if (!isPrevTickPressed) {

                double x = StdDraw.mouseX();
                double y = StdDraw.mouseY();
                curClickPosition = getIntersectPos(x, y, boardView.getPositions());
            }

            isPrevTickPressed = true;
        } else {
            if (isPrevTickPressed) {
                isPrevTickPressed = false;
                double x = StdDraw.mouseX();
                double y = StdDraw.mouseY();
                Position releasePos = getIntersectPos(x, y, boardView.getPositions());
                if (releasePos == curClickPosition && curClickPosition != null) {
                    game.clickedPos(releasePos);
                }
                curClickPosition = null;
            }
        }
    }

    /**
     The draw method is responsible for rendering the game interface. It calls the draw methods of the various
     components and the draw method of HintManager.
     */
    public void draw() {
        drawBoard();
        drawAside();
        drawPoints();
        hintView.draw();
        hintButton.draw();
        tutorView.draw();
        tutorButton.draw();
        HintManager.getInstance().draw();
    }

    private void drawPoints() {
        posPainter.draw();
    }

    private void drawAside() {
        aside.draw();
    }

    private void drawBoard() {
        boardView.draw();
    }

    private void temptDraw() {
    }

    public void drawGameCompleteScreen() {
    }

    public void drawGameOver() {
        StdDraw.setPenColor(StdDraw.RED);
        Font font = new Font("Arial", Font.BOLD, 50);
        StdDraw.setFont(font);


        StdDraw.text(0, 0, game.getWinner());
    }
}
