package de.thm.codecracker.game;

import de.thm.codecracker.game.model.GameResult;
import de.thm.codecracker.game.model.SecretCode;
import de.thm.codecracker.user.UserRepository;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.Future;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verwaltet das einzige aktuell laufende Spiel (falls vorhanden): den
 * Geheimcode, die aktive Runde, abgegebene Tipps, Zeitsteuerung, die
 * Gewinnermittlung sowie die Persistierung der Punktestände.
 *
 * <p>Es läuft immer nur ein Spiel gleichzeitig, analog zum
 * {@code gameInProgress}-Flag von {@code LobbyWebSocketController}. Sobald
 * ein Spiel endet, wird {@link #onGameEnded} aufgerufen, damit die Lobby
 * ihren eigenen Zustand zurücksetzen kann.</p>
 *
 * <p>Die Gewinnermittlung folgt der README-Vorgabe: Errät genau ein Spieler
 * den Code, gewinnt dieser (3 Punkte); erraten mehrere Spieler in derselben
 * Runde, erhalten alle davon ein Unentschieden (1 Punkt) und alle übrigen
 * verlieren (-1 Punkt); errät bis zum Rundenlimit niemand den Code, verlieren
 * alle aktiven Spieler. Ein vorzeitig verlassenes Spiel ({@link #leaveGame})
 * zählt für den betroffenen Spieler ebenfalls als Niederlage. Siehe
 * {@link #resultFor}.</p>
 */
public class GameService {
  private static final Logger LOGGER = LoggerFactory.getLogger(GameService.class);

  private final Vertx vertx;
  private final UserRepository userRepository;
  private final GameRepository gameRepository;
  private final HighscoreRepository highscoreRepository;

  private Runnable onGameEnded;

  private boolean gameInProgress = false;
  private Set<String> participants = Set.of();
  private SecretCode secretCode;
  private int roundNumber;
  private long roundDeadline;
  private long timerId = -1;
  private Long dbGameId;

  private final Map<String, List<String>> currentGuesses = new HashMap<>();
  private final Map<String, ServerWebSocket> sockets = new ConcurrentHashMap<>();
  private final Set<String> leftPlayers = ConcurrentHashMap.newKeySet();

  /**
   * Erstellt einen neuen {@code GameService}.
   *
   * @param vertx          die Vert.x-Instanz, u. a. für Runden-Timer
   * @param userRepository Repository zum Auflösen von Benutzernamen auf User-IDs für die Score-Persistierung
   * @param gameRepository Repository zum Persistieren von Spielen und Punktestanden
   */
  public GameService(Vertx vertx, UserRepository userRepository, GameRepository gameRepository, HighscoreRepository highscoreRepository) {
    this.vertx = vertx;
    this.userRepository = userRepository;
    this.gameRepository = gameRepository;
    this.highscoreRepository = highscoreRepository;
  }

  /**
   * Registriert einen Callback, der aufgerufen wird, sobald das aktuelle
   * Spiel endet (regulär oder durch {@link #forceEnd()}).
   *
   * @param onGameEnded der auszuführende Callback, z. B. um die Lobby freizugeben
   */
  public synchronized void setOnGameEnded(Runnable onGameEnded) {
    this.onGameEnded = onGameEnded;
  }

  /**
   * Bricht ein laufendes Spiel zwangsweise ab und setzt den kompletten
   * Zustand zurück, unabhängig vom Rundenstatus.
   *
   
   * (siehe README: "muss vor der Abgabe entfernt werden"). Informiert alle
   * verbundenen Spieler per {@code game-aborted}-Nachricht und ruft
   * anschließend {@link #onGameEnded} auf. Es wird bewusst keine
   * Punktevergabe für ein zwangsweise abgebrochenes Spiel vorgenommen.</p>
   */
  public synchronized void forceEnd() {
    if (timerId != -1) {
      vertx.cancelTimer(timerId);
      timerId = -1;
    }

    for (ServerWebSocket socket : sockets.values()) {
      sendTo(socket, "game-aborted", new JsonObject()
        .put("message", "Das Spiel wurde beendet."));
    }

    gameInProgress = false;
    participants = Set.of();
    secretCode = null;
    dbGameId = null;
    currentGuesses.clear();
    leftPlayers.clear();
    sockets.clear();
    LOGGER.info("Game state forcibly cleared");

    if (onGameEnded != null) onGameEnded.run();
  }

  /**
   * Prüft, ob der angegebene Nutzer aktiver Teilnehmer des laufenden Spiels ist.
   *
   * <p>Ein Nutzer, der das Spiel bereits über {@link #leaveGame} verlassen
   * hat, gilt nicht mehr als Teilnehmer, auch wenn er ursprünglich Teil der
   * {@code participants}-Menge war.</p>
   *
   * @param username der zu prüfende Benutzername
   * @return {@code true}, wenn ein Spiel läuft und der Nutzer aktiv daran teilnimmt
   */
  public synchronized boolean isParticipant(String username) {
    return gameInProgress && participants.contains(username) && !leftPlayers.contains(username);
  }

  /**
   * Startet ein neues Spiel für die übergebenen Teilnehmer.
   *
   * <p>Wird aufgerufen, sobald die Lobby eine Einladung erfolgreich auflöst
   * (mindestens zwei bereite Spieler). Generiert einen neuen Geheimcode,
   * setzt den Rundenzähler zurück und persistiert das Spiel als neuen
   * Datensatz in {@code games}.</p>
   *
   * @param usernames die Benutzernamen aller Teilnehmer dieses Spiels
   */
  public synchronized void startGame(Set<String> usernames) {
    if (gameInProgress) {
      LOGGER.warn("startGame called while a game is already in progress; ignoring");
      return;
    }

    this.participants = Set.copyOf(usernames);
    this.secretCode = SecretCode.generateRandom();
    this.roundNumber = 1;
    this.currentGuesses.clear();
    this.leftPlayers.clear();
    this.gameInProgress = true;

    gameRepository.create(String.join(",", secretCode.colors()), GameConfig.MAX_ROUNDS)
      .onSuccess(id -> dbGameId = id)
      .onFailure(err -> LOGGER.error("Failed to persist new game", err));

    scheduleRoundTimer();
  }

  /**
   * Verknüpft einen frisch verbundenen WebSocket mit einem Teilnehmer und
   * sendet ihm den aktuellen Rundenstatus, damit er (z. B. nach Reload) sofort
   * wieder synchron ist.
   *
   * @param username der Benutzername des Teilnehmers
   * @param socket   der neu verbundene WebSocket
   */
  public synchronized void registerSocket(String username, ServerWebSocket socket) {
    sockets.put(username, socket);
    LOGGER.info("Socket registered for {} (now {} of {} participants connected)",
      username, sockets.size(), participants.size());
    sendRoundStart(socket);
  }

  /**
   * Entfernt die Zuordnung eines WebSockets zu einem Teilnehmer, z. B. wenn
   * die Verbindung geschlossen wurde.
   *
   * @param username der Benutzername des Teilnehmers
   * @param socket   der zu entfernende WebSocket (nur entfernt, wenn er noch der aktuell registrierte ist)
   */
  public synchronized void unregisterSocket(String username, ServerWebSocket socket) {
    sockets.remove(username, socket);
  }

  /**
   * Nimmt den Tipp eines Spielers für die aktuelle Runde entgegen.
   *
   * <p>Sobald alle noch aktiven (nicht ausgeschiedenen) Teilnehmer einen
   * Tipp abgegeben haben, wird die Runde sofort ausgewertet, ohne auf das
   * Zeitlimit zu warten.</p>
   *
   * @param username der Benutzername des tippenden Spielers
   * @param guess    der abgegebene Farbcode, muss genau {@link GameConfig#CODE_LENGTH} gültige Farben enthalten
   * @throws IllegalArgumentException wenn der Spieler nicht (mehr) am Spiel
   *         teilnimmt, bereits in dieser Runde getippt hat, oder der Tipp ungültig ist
   */
  public synchronized void submitGuess(String username, List<String> guess) {
    if (!gameInProgress || !participants.contains(username) || leftPlayers.contains(username)) {
      throw new IllegalArgumentException("You are not part of the current game");
    }
    if (currentGuesses.containsKey(username)) {
      throw new IllegalArgumentException("You already submitted a guess this round");
    }
    if (guess == null || guess.size() != GameConfig.CODE_LENGTH || !GameConfig.COLORS.containsAll(guess)) {
      throw new IllegalArgumentException("Guess must contain exactly " + GameConfig.CODE_LENGTH + " valid colors");
    }

    currentGuesses.put(username, guess);
    LOGGER.info("Guess received from {} ({} of {} active participants have guessed this round)",
      username, currentGuesses.size(), activeParticipantCount());

    checkRoundComplete();
  }

  /**
   * Setzt den Ablaufzeitpunkt der aktuellen Runde und startet einen Timer,
   * der die Runde bei Zeitablauf automatisch auswertet.
   */
  private void scheduleRoundTimer() {
    roundDeadline = System.currentTimeMillis() + GameConfig.ROUND_DURATION_MS;
    timerId = vertx.setTimer(GameConfig.ROUND_DURATION_MS, id -> resolveRound());
  }

  /**
   * Wertet die aktuelle Runde aus: vergleicht alle abgegebenen Tipps mit dem
   * Geheimcode, ermittelt die Gewinner dieser Runde und sendet jedem
   * Teilnehmer sein individuelles Feedback (richtige Position/Farbe).
   *
   * <p>Endet das Spiel nicht (kein Gewinner und Rundenlimit noch nicht
   * erreicht), wird die nächste Runde vorbereitet und per Broadcast
   * angekündigt.</p>
   */
  private synchronized void resolveRound() {
    if (!gameInProgress) return;
    LOGGER.info("Resolving round {} for participants {} (guesses from {})",
      roundNumber, participants, currentGuesses.keySet());

    Set<String> winners = new HashSet<>();

    for (String username : participants) {
      if (leftPlayers.contains(username)) continue;

      List<String> guess = currentGuesses.get(username);
      ServerWebSocket socket = sockets.get(username);
      if (guess == null) {
        sendTo(socket, "round-result", new JsonObject()
          .put("round", roundNumber)
          .put("submitted", false));
        continue;
      }

      SecretCode.GuessResult result = secretCode.evaluate(guess);
      if (result.exact()) winners.add(username);

      sendTo(socket, "round-result", new JsonObject()
        .put("round", roundNumber)
        .put("submitted", true)
        .put("black", result.black())
        .put("white", result.white())
        .put("exact", result.exact()));
    }

    if (!winners.isEmpty() || roundNumber >= GameConfig.MAX_ROUNDS || activeParticipantCount() == 0) {
      endGame(winners);
    } else {
      roundNumber++;
      currentGuesses.clear();
      scheduleRoundTimer();
      broadcast("round-advance", new JsonObject()
        .put("round", roundNumber)
        .put("maxRounds", GameConfig.MAX_ROUNDS)
        .put("remainingMs", GameConfig.ROUND_DURATION_MS));
    }
  }

  /**
   * Beendet das aktuelle Spiel: bestimmt für jeden Teilnehmer das Ergebnis
   * (Sieg/Unentschieden/Niederlage) über {@link #resultFor}, informiert alle
   * Clients per {@code game-over}-Nachricht, persistiert die Punktestände und
   * setzt anschließend den internen Zustand zurück.
   *
   * @param winners die Menge der Teilnehmer, die den Geheimcode in der
   *                letzten ausgewerteten Runde exakt erraten haben (kann leer sein)
   */
  private synchronized void endGame(Set<String> winners) {
    Long finishedGameId = dbGameId;
    Set<String> finishedParticipants = participants;
    Map<String, ServerWebSocket> finishedSockets = Map.copyOf(sockets);
    int finishedRound = roundNumber;
    List<String> finishedSecretCode = secretCode.colors();
    String status = winners.isEmpty() ? "FINISHED_NO_WINNER" : "FINISHED";
 
    List<Future<JsonObject>> summaries = finishedParticipants.stream()
      .map(username -> buildPlayerSummary(finishedGameId, username, winners))
      .toList();
 
    Future.all(summaries).onComplete(ar -> {
      JsonArray playersJson = new JsonArray();
      if (ar.succeeded()) {
        summaries.forEach(f -> playersJson.add(f.result()));
      } else {
        LOGGER.error("Failed to build player summaries for game {}", finishedGameId, ar.cause());
      }
 
      JsonObject basePayload = new JsonObject()
        .put("round", finishedRound)
        .put("winners", new JsonArray(List.copyOf(winners)))
        .put("secretCode", new JsonArray(finishedSecretCode))
        .put("players", playersJson);
 
      for (String username : finishedParticipants) {
        GameResult result = resultFor(username, winners);
        sendTo(finishedSockets.get(username), "game-over", basePayload.copy()
          .put("result", result.name())
          .put("points", result.points()));
      }
    });
 
    if (finishedGameId != null) {
      gameRepository.markFinished(finishedGameId, status)
        .onFailure(err -> LOGGER.error("Failed to mark game {} as finished", finishedGameId, err));
    }
 
    gameInProgress = false;
    participants = Set.of();
    secretCode = null;
    dbGameId = null;
    currentGuesses.clear();
    leftPlayers.clear();
    sockets.clear();
 
    if (onGameEnded != null) onGameEnded.run();
  }

  private Future<JsonObject> buildPlayerSummary(Long gameId, String username, Set<String> winners) {
    GameResult result = resultFor(username, winners);
    int points = result.points();
 
    return userRepository.findByUsername(username)
      .compose(user -> {
        if (user == null) {
          return Future.succeededFuture(new JsonObject()
            .put("username", username)
            .put("result", result.name())
            .put("points", points)
            .putNull("totalPoints"));
        }
 
        Future<Void> persisted = gameId != null
          ? gameRepository.addPlayerScore(gameId, user.id(), points)
          : Future.succeededFuture();
 
        return persisted
          .recover(err -> {
            LOGGER.error("Failed to save score for {}", username, err);
            return Future.succeededFuture();
          })
          .compose(v -> highscoreRepository.totalScoreForUser(user.id()))
          .map(total -> new JsonObject()
            .put("username", username)
            .put("result", result.name())
            .put("points", points)
            .put("totalPoints", total));
      })
      .recover(err -> {
        LOGGER.error("Failed to look up user {} for scoring", username, err);
        return Future.succeededFuture(new JsonObject()
          .put("username", username)
          .put("result", result.name())
          .put("points", points)
          .putNull("totalPoints"));
      });
  }

  /**
   * Sendet einem neu verbundenen Socket den aktuellen Rundenstatus
   * (Rundennummer, Konfiguration, verbleibende Zeit), sofern gerade ein
   * Spiel läuft.
   *
   * @param socket der Ziel-WebSocket
   */
  private void sendRoundStart(ServerWebSocket socket) {
    if (!gameInProgress) return;
    long remainingMs = Math.max(0, roundDeadline - System.currentTimeMillis());
    sendTo(socket, "round-start", new JsonObject()
      .put("round", roundNumber)
      .put("maxRounds", GameConfig.MAX_ROUNDS)
      .put("codeLength", GameConfig.CODE_LENGTH)
      .put("colors", new JsonArray(GameConfig.COLORS))
      .put("remainingMs", remainingMs));
  }

  /**
   * Sendet eine einzelne Nachricht an einen bestimmten Socket, sofern dieser
   * noch verbunden ist.
   *
   * @param socket  der Ziel-WebSocket, darf {@code null} oder bereits geschlossen sein
   * @param type    der Nachrichtentyp (z. B. {@code "round-result"})
   * @param payload die Nutzdaten der Nachricht
   */
  private void sendTo(ServerWebSocket socket, String type, JsonObject payload) {
    if (socket == null || socket.isClosed()) return;
    socket.writeTextMessage(new JsonObject().put("type", type).put("payload", payload).encode());
  }

  /**
   * Sendet eine Nachricht an alle aktuell verbundenen Sockets des Spiels.
   *
   * @param type    der Nachrichtentyp
   * @param payload die Nutzdaten der Nachricht
   */
  private void broadcast(String type, JsonObject payload) {
    for (ServerWebSocket socket : sockets.values()) {
      sendTo(socket, type, payload);
    }
  }

  /**
   * Bestimmt das Spielergebnis eines einzelnen Teilnehmers gemäß der
   * README-Gewinnermittlung:
   * <ul>
   *   <li>hat der Spieler das Spiel vorzeitig verlassen oder nicht gewonnen → {@link GameResult#LOSE}</li>
   *   <li>genau ein Gewinner insgesamt und dieser Spieler ist es → {@link GameResult#WIN}</li>
   *   <li>mehrere Gewinner und dieser Spieler ist einer davon → {@link GameResult#DRAW}</li>
   * </ul>
   *
   * @param username der zu bewertende Benutzername
   * @param winners  die Menge aller Gewinner dieses Spiels
   * @return das Ergebnis (Sieg, Unentschieden oder Niederlage) für diesen Spieler
   */
  private GameResult resultFor(String username, Set<String> winners) {
    if (leftPlayers.contains(username)) {
      return GameResult.LOSE;
    }
    if (!winners.contains(username)) {
      return GameResult.LOSE;
    }
    return winners.size() == 1 ? GameResult.WIN : GameResult.DRAW;
  }

  /**
   * Liefert die Anzahl der Teilnehmer, die weder das Spiel verlassen haben
   * noch anderweitig ausgeschieden sind.
   *
   * @return die Anzahl aktuell aktiver Teilnehmer
   */
  private long activeParticipantCount() {
    return participants.stream().filter(u -> !leftPlayers.contains(u)).count();
  }

  /**
   * Prüft, ob alle noch aktiven (nicht ausgeschiedenen) Teilnehmer bereits
   * einen Tipp für die aktuelle Runde abgegeben haben, bzw. ob niemand mehr
   * aktiv ist, und löst in diesen Fällen sofort die Rundenauswertung aus
   * (statt auf das Zeitlimit zu warten).
   */
  private void checkRoundComplete() {
    long activeCount = activeParticipantCount();

    if (activeCount == 0) {
      // Alle haben das Spiel verlassen -> Spiel beenden, niemand gewinnt
      vertx.cancelTimer(timerId);
      resolveRound();
      return;
    }

    if (currentGuesses.size() >= activeCount) {
      LOGGER.info("All active participants have guessed, resolving round {}", roundNumber);
      vertx.cancelTimer(timerId);
      resolveRound();
    }
  }

  /**
   * Lässt einen Spieler das laufende Spiel vorzeitig verlassen. Der Spieler
   * zählt ab diesem Zeitpunkt gemäß README als "verloren" (siehe
   * {@link #resultFor}) und nimmt an weiteren Runden nicht mehr teil.
   *
   * <p>Verlassen alle Teilnehmer das Spiel, wird es sofort ohne Gewinner
   * beendet (siehe {@link #checkRoundComplete}).</p>
   *
   * @param username der Benutzername des verlassenden Spielers
   * @throws IllegalArgumentException wenn kein Spiel läuft oder der Nutzer nicht Teilnehmer ist
   */
  public synchronized void leaveGame(String username) {
    if (!gameInProgress || !participants.contains(username)) {
      throw new IllegalArgumentException("You're not part of the current game");
    }
    if (!leftPlayers.add(username)) {
      return; // hat bereits verlassen
    }
    currentGuesses.remove(username);
    ServerWebSocket socket = sockets.remove(username);
    sendTo(socket, "left-game", new JsonObject());

    broadcast("player-left", new JsonObject().put("username", username));

    checkRoundComplete();
  }
}