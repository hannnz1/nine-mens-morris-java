package Manager;

import Logic.Hint;

import java.util.ArrayList;

public class HintManager {

    // ArrayList to store the hints
    ArrayList<Hint> hints = new ArrayList<>();

    // Instance of the class
    private static HintManager tm = null;

    // Singleton Design Pattern: Get the instance of HintManager
    public static HintManager getInstance() {
        if (tm == null) {
            tm = new HintManager();
        }

        return tm;
    }

    // Add a new hint to the list
    public void newTips(String str) {
        hints.add(new Hint(str));
    }

    // Update the hints based on the delta time
    public void update(double delta) {

        ArrayList<Hint> dead = new ArrayList<>();
        for (var t : hints) {
            t.update(delta);
            if (t.over()) {
                dead.add(t);
            }
        }
        // Remove the hints that have reached their end
        for (var d : dead) {
            hints.remove(d);
        }
    }
    // Draw the hints on the screen
    public void draw() {
        for (var t: hints) {
            t.draw();
        }
    }

}
