package Logic;

public class Position{
    private double x; // Stores the x-coordinate of the position
    private double y; // Stores the y-coordinate of the position

    private boolean isClickedDebug = false; // Debug flag to track if the position is clicked


    private double clickRadius; // Radius within which the position is considered clicked
    private PositionStatus status; // Status of the position
    private int index = -1; // Index of the position
    private static int s_index = 0; // Static index for all positions

    public Position(double x, double y) {
        this.x = x; // Initializes the x-coordinate
        this.y = y; // Initializes the y-coordinate
        this.clickRadius = 0.12; // Sets the default click radius
        this.status = PositionStatus.EMPTY; // Sets the initial status of the position to "EMPTY"
        index = s_index++; // Assigns a unique index to the position
    }



    public double getX() {
        return x;
    } // Returns the x-coordinate

    public double getY() {
        return y;
    } // Returns the y-coordinate

    public double getClickRadius() {
        return clickRadius;
    } // Returns the click radius

    public PositionStatus getStatus() {
        return status;
    } // Returns the status of the position

    public void setStatus(PositionStatus status) {
        this.status = status;
    } // Updates the status of the position

}