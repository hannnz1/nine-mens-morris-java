package View;

import Logic.Game;

public class TutorialView {
    private Game v;
    private double t;


    private int tutorStatus = 0;

    public TutorialView(Game view) {
        this.v = view;
    }

    public void update(double time_step) {
        t += time_step;
    }

    public void draw() {
        if(tutorStatus == 0) {
        }
        else {
            StdDraw.picture(0, 0, ("images/image_" + Integer.toString(tutorStatus)) + ".png", 2, 2);
        }
    }

    public void setTutorStatus() {
        tutorStatus = (tutorStatus + 1) % 6;
    }



}

