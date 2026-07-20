# Nine Men's Morris

[![Java CI](https://github.com/hannnz1/nine-mens-morris-java/actions/workflows/ci.yml/badge.svg)](https://github.com/hannnz1/nine-mens-morris-java/actions/workflows/ci.yml)

A Java desktop implementation of **Nine Men's Morris**, developed as a team project for Monash University **FIT3077 - Software Engineering: Architecture and Design (Semester 1, 2023)** by **Group 43**.

The application supports the complete local two-player game flow, including piece placement, adjacent movement, flying when a player has three pieces remaining, mill detection, piece removal, hints, tutorials, and win detection.

![Nine Men's Morris gameplay](<Screenshots/Hints prompting Black selection.png>)

## Features

- Local two-player gameplay using mouse controls.
- All three phases of Nine Men's Morris:
  - placing pieces on empty positions;
  - moving pieces to adjacent positions;
  - flying to any empty position when only three pieces remain.
- Mill detection and opponent-piece removal.
- Validation of selectable pieces and legal destinations.
- Animated visual hints for valid actions.
- Five-page in-game tutorial.
- Turn, remaining-piece, removed-piece, and winner displays.

## Architecture

The project separates game rules, state management, and rendering into several packages:

- `Logic` - game state, positions, piece states, mill detection, turn handling, and win conditions.
- `Manager` - legal-action candidate calculation and temporary hint management.
- `View` - board rendering, controls, tutorials, indicators, and mouse interaction.
- `Engine` - application entry point and main update/render loop.

The board is represented as a graph: each of the 24 positions is a vertex, and legal adjacent movements are stored as edges. `CandidateMgr` derives the currently legal positions from the game phase and state.

```text
project-main/
|-- Nine Man's Morris/
|   `-- src/
|       |-- Engine.java
|       |-- Logic/
|       |-- Manager/
|       |-- View/
|       `-- images/
|-- Design Rationale/
|-- Revised Architecture/
|-- Sequence Diagrams/
|-- Screenshots/
|-- UI Design/
|-- Group43_Domain_Model.pdf
`-- Group43_Text.pdf
```

## Requirements

- JDK 17 recommended (the project is compiled with Java 15 compatibility)
- Maven 3.9+, IntelliJ IDEA, or Docker

The project does not require external runtime dependencies or a database.

## Build and Test with Maven

From the repository root:

```powershell
mvn clean verify
```

This command compiles the application, runs the JUnit test suite, and packages an executable JAR. Run the packaged application with:

```powershell
java -jar .\target\nine-mens-morris-1.0.0-SNAPSHOT.jar
```

The automated tests currently cover:

- the 24-position board topology and bidirectional edges;
- the initial placement state and legal candidates;
- turn and piece-count transitions;
- rejection of occupied placement positions;
- mill formation and opponent-piece removal.

## Test with Docker

If Maven or a JDK is not installed locally, run the same test suite in an isolated container:

```powershell
docker run --rm `
  -v "${PWD}:/workspace" `
  -w /workspace `
  maven:3.9.9-eclipse-temurin-17 `
  mvn --batch-mode --no-transfer-progress clean verify
```

Every push and pull request to `main` also runs `mvn verify` through GitHub Actions.

## Run with IntelliJ IDEA

1. Open the `Nine Man's Morris` directory in IntelliJ IDEA.
2. Set the Project SDK and language level to Java 15.
3. Confirm that `src` is marked as the Sources Root.
4. Open `src/Engine.java` and run `Engine.main()`.

A window titled `Standard Draw` will open. Use the mouse to select board positions and the **Hint** and **Tutorial** buttons.

## Run from PowerShell

From the repository root:

```powershell
Set-Location ".\Nine Man's Morris\src"
New-Item -ItemType Directory -Force "..\out\classes" | Out-Null
$sourceFiles = Get-ChildItem -Recurse -Filter "*.java" | ForEach-Object FullName
javac -encoding UTF-8 -d "..\out\classes" $sourceFiles
java -cp "..\out\classes" Engine
```

Running from the `src` directory ensures the tutorial images under `src/images` are resolved correctly.

## Game Rules Implemented

1. Players alternately place nine pieces on empty positions.
2. After all pieces are placed, players move one piece to an adjacent empty position each turn.
3. Forming a line of three pieces (a mill) allows the player to remove one eligible opponent piece.
4. A player with exactly three pieces may move to any empty position.
5. A player loses when fewer than three pieces remain or no legal move is available.

## Design Documentation

- [Domain model](Group43_Domain_Model.pdf)
- [Revised class diagram](<Revised Architecture/Group43_Revised_Class_Diagram.pdf>)
- [Sprint 4 design rationale](Design%20Rationale/Group_43_Sprint_4_Written_Work.pdf)
- [Sequence diagrams](<Sequence Diagrams>)
- [UI designs](<UI Design>)
- [Gameplay screenshots](Screenshots)

## Current Limitations

- Local two-player mode only; there is no network multiplayer or computer opponent.
- The original source layout is retained for compatibility with the university submission; a future refactor will move the domain engine into a conventional Maven module.
- The initial test suite focuses on placement and mill rules; movement, flying, and end-game scenarios need additional coverage.
- Game state is stored in memory and is not persisted between sessions.

## Third-Party Component

Rendering and input handling use an adapted copy of Princeton University's `StdDraw`, authored by Robert Sedgewick and Kevin Wayne. See the [Princeton StdDraw documentation](https://introcs.cs.princeton.edu/java/stdlib/StdDraw.java.html) and the source attribution in `src/View/StdDraw.java`.

## Academic Context

This repository is an archived copy of a collaborative university project. The Git history begins with the GitHub import and does not contain the original Monash GitLab commit history.
