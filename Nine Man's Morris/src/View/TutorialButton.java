package View;

import java.awt.*;

public class TutorialButton {
    private final TutorialView tutor;
    private boolean isPrevTickPressed;

    private boolean pressedHere = false;



    public TutorialButton(TutorialView tutor) {
        this.tutor = tutor;
    }

    /**
     It has an update method that is called to check if the button is being pressed. Inside the update
     method, it checks the mouse state and coordinates to determine if the button is being pressed or
     released. If the button is pressed, the clicked method is called, which prints "clicked" and updates
     the tutorial status in the TutorialView object.
     */
    public void update(double delta) {

        if (StdDraw.isMousePressed()) {
            if (!isPrevTickPressed) {

                double x = StdDraw.mouseX();
                double y = StdDraw.mouseY();
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
                if (inbound(x, y) && pressedHere) {
                    clicked();
                }
            }
        }
    }



    private void clicked() {
        System.out.println("clicked");
        tutor.setTutorStatus();
    }

    private boolean inbound(double x, double y) {
        return x >= 0.8 - 0.12 && x <= 0.8 + 0.12 && y >= 0.9 - 0.05 && y <= 0.9 + 0.05;
    }

    public void draw() {
        StdDraw.setPenColor(StdDraw.BOOK_BLUE);
        StdDraw.filledRectangle(0.8, 0.9, 0.12, 0.05);

        StdDraw.setPenColor(StdDraw.WHITE);
        Font font = new Font("Arial", Font.BOLD, 24);
        StdDraw.setFont(font);
        StdDraw.text(0.8, 0.9, "Tutorial");
    }
}

