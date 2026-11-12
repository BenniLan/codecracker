package de.thm.codecracker.game.model;

public enum GameResult {
    WIN(3), DRAW(1), LOSE(-1);

    private final int points;

    GameResult(int points){
        this.points = points;
    }

    public int points(){
        return points;
    }
    
}
