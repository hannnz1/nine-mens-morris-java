package Logic;

import View.StdDraw;

import java.awt.*;

public class Hint {
    private final String words; // Stores the hint words
    private double t; // Represents the elapsed time


    public Hint(String words) {
        this.words = words;
    } // Initializes the hint words

    public void update(double delta) {
        t += delta;
    } // Updates the elapsed time by adding the delta value

    public boolean over() {
        return t > 3;
    } // Returns true if the elapsed time is greater than 3.0

    public void draw() {
        // Configures the drawing settings
        StdDraw.setPenColor(StdDraw.BOOK_RED);
        Font font = new Font("Arial", Font.PLAIN, 18);
        StdDraw.setFont(font);
        // Draws the hint text on the screen
        StdDraw.textRight(0.8, 0.8, words);
    }
}
