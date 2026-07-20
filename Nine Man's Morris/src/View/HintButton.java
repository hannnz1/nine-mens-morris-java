package View;

import java.awt.*;

public class HintButton {
    private final HintView hint;
    private boolean isPrevTickPressed;

    private boolean pressedHere = false;

    public HintButton(HintView hint) {
        this.hint = hint;
    }

    public void update(double delta) {
        // Check if the mouse button is pressed
        if (StdDraw.isMousePressed()) {
            // If the button was not pressed in the previous tick
            if (!isPrevTickPressed) {
                // Get the mouse coordinates
                double x = StdDraw.mouseX();
                double y = StdDraw.mouseY();
                // Check if the mouse click is within the bounds of the hint button
                if (inbound(x, y)) {
                    pressedHere = true;
                }
            }

            isPrevTickPressed = true;
        } else {
            if (isPrevTickPressed) {
                isPrevTickPressed = false;
                double x = StdDraw.mouseX();
                double y = StdDraw.mouseY();
                // Check if the mouse release is within the bounds of the hint button
                if (inbound(x, y) && pressedHere) {
                    // Perform the desired action when the hint button is clicked
                    clicked();
                }
            }
        }
    }

    private void clicked() {
        System.out.println("clicked");
        hint.setHint(!hint.isHint());
    }

    private boolean inbound(double x, double y) {
        return x >= -0.8 - 0.12 && x <= -0.8 + 0.12 && y >= 0.9 - 0.05 && y <= 0.9 + 0.05;
    }

    public void draw() {
        StdDraw.setPenColor(StdDraw.BOOK_BLUE);
        StdDraw.filledRectangle(-0.8, 0.9, 0.12, 0.05);

        StdDraw.setPenColor(StdDraw.WHITE);
        Font font = new Font("Arial", Font.BOLD, 24);
        StdDraw.setFont(font);
        StdDraw.text(-0.8, 0.9, "Hint");
    }
}
