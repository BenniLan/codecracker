package de.thm.codecracker.game.model;

import de.thm.codecracker.game.GameConfig;

import java.security.SecureRandom;
import java.util.List;


public record SecretCode(List<String> colors) {
  public record GuessResult(int black, int white, boolean exact) {}
  private static final SecureRandom RANDOM = new SecureRandom();

  public static SecretCode generateRandom() {
    List<String> generated = RANDOM.ints(GameConfig.CODE_LENGTH, 0, GameConfig.COLORS.size())
      .mapToObj(GameConfig.COLORS::get)
      .toList();
    return new SecretCode(generated);
  }

  /**
   * "black" = correct color in the correct position.
   * "white" = correct color, wrong position (each secret/guess slot counted at most once).
   */
  public GuessResult evaluate(List<String> guess) {
    if (guess.size() != colors.size()) {
      throw new IllegalArgumentException("Guess must have exactly " + colors.size() + " colors");
    }

    boolean[] secretMatched = new boolean[colors.size()];
    boolean[] guessMatched = new boolean[colors.size()];
    int black = 0;

    for (int i = 0; i < colors.size(); i++) {
      if (colors.get(i).equals(guess.get(i))) {
        black++;
        secretMatched[i] = true;
        guessMatched[i] = true;
      }
    }

    int white = 0;
    for (int i = 0; i < colors.size(); i++) {
      if (guessMatched[i]) continue;
      for (int j = 0; j < colors.size(); j++) {
        if (!secretMatched[j] && guess.get(i).equals(colors.get(j))) {
          white++;
          secretMatched[j] = true;
          break;
        }
      }
    }

    boolean exact = black == colors.size();
    return new GuessResult(black, white, exact);
  }
}