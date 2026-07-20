import View.View;
import View.StdDraw;

public class Engine {

    //The Engine class. Responsible for creating the View. View, and then controlling the game flow when the game continues.
    public static void main(String[] args) {

        StdDraw.setCanvasSize(850, 850);
        StdDraw.setScale(-1, +1);
        StdDraw.enableDoubleBuffering();
        View game = new View();

        while(!game.gameOver()) {
            game.update(0.02);
            StdDraw.clear();
            game.draw();
            StdDraw.show();
            StdDraw.pause(20);
        }

        StdDraw.clear();
        game.drawGameOver();
        StdDraw.show();
    }
}