package de.thm.codecracker.lobby;

import de.thm.codecracker.game.GameService;
import de.thm.codecracker.lobby.model.GameInvite;
import de.thm.codecracker.lobby.model.LobbyPlayer;
import de.thm.codecracker.lobby.model.LobbyStatus;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import io.vertx.ext.auth.User;


public class LobbyWebSocketController {
  private static final Logger LOGGER = LoggerFactory.getLogger(LobbyWebSocketController.class);
  private static final String LOBBY_PATH = "/ws/lobby";
  private static final long INVITE_TIMEOUT_MS = 30_000;
  private static final int MIN_READY_PLAYER = 2;

  private final Vertx vertx;
  private final JWTAuth jwtAuth;
  private final GameService gameService;
  private final Map<ServerWebSocket, LobbyPlayer> players = new ConcurrentHashMap<>();

  private boolean gameInProgress = false;
  private GameInvite activeInvite = null;
  private Set<String> playingUsernames = Set.of();

  public LobbyWebSocketController(JWTAuth jwtAuth, Vertx vertx, GameService gameService) {
    this.jwtAuth = jwtAuth;
    this.vertx = vertx;
    this.gameService = gameService;
  }

  public void registerRoutes(Router router) {
    router.get(LOBBY_PATH).handler(this::upgradeConnection);
    router.get("/api/game/participation").handler(this::checkAnticipation);
  }

  /** Called by GameService once a game finishes, so the lobby can reset. */
  public synchronized void markGameEnded() {
    gameInProgress = false;
    playingUsernames = Set.of();
    broadcastLobbyState();
  }

  private void upgradeConnection(RoutingContext context) {
    String token = context.request().getParam("token");

    if(token == null){
        context.response().setStatusCode(401).end("Missing token");
        return;
    }
    try{
        var jwtUser = jwtAuth.authenticate(new TokenCredentials(token)).await();
        String username = jwtUser.principal().getString("sub");
        String role = jwtUser.principal().getString("role");

        ServerWebSocket socket = context.request().toWebSocket().await();
        handleConnection(socket, username, role);
    }   catch (Exception exception){
        LOGGER.error("Lobby WebSocket auth failed", exception);
        context.response().setStatusCode(401).end("Invalid token");
    }
  }

  private void handleCommand(ServerWebSocket socket, String message){
    try{
      JsonObject command = new JsonObject(message);
      String type = command.getString("type");

      switch (type){
        case "start-game" -> startGame(socket);
        case "respond" -> respond(socket, command.getJsonObject("payload", new JsonObject()));
        case "reset-game" -> resetGame(socket);
        default -> LOGGER.warn("Unknown lobby command", type);
      }
    }catch (Exception exception){
      LOGGER.error("Failed to handle lobby command", exception);
    }
  }

  private synchronized void startGame(ServerWebSocket initiatorSocket){
    if(gameInProgress){
      sendError(initiatorSocket, "Es läuft bereits ein Spiel");
      return;
    }
    if(activeInvite != null){
      sendError(initiatorSocket, "Es gibt bereits eine ausstehende Einladung");
      return;
    }

    LobbyPlayer initiator = players.get(initiatorSocket);
    if(initiator == null) return;

    Set<ServerWebSocket> invited = ConcurrentHashMap.newKeySet();
    invited.addAll(players.keySet());
    invited.remove(initiatorSocket);

    if(invited.isEmpty()){
      sendError(initiatorSocket, "Es sind keine anderen Spieler in der Lobby");
    }

    for(var socket : invited){
      players.computeIfPresent(socket, (s, player) -> player.withStatus(LobbyStatus.PENDING));
    }
    broadcastLobbyState();

    long timerId = vertx.setTimer(INVITE_TIMEOUT_MS, id -> resolveInvite());
    activeInvite = GameInvite.create(initiatorSocket, invited, timerId);

    JsonObject inviteMessage = new JsonObject()
      .put("type", "game-invite")
      .put("payload", new JsonObject().put("initiator", initiator.username()));

    for (var socket : invited) {
      socket.writeTextMessage(inviteMessage.encode());
    }
  }

  private synchronized void resetGame(ServerWebSocket socket){
    gameInProgress = false;
    playingUsernames = Set.of();
    gameService.forceEnd();
    LOGGER.info("Game manually ended via debug command");
    broadcastLobbyState();
  }

  private synchronized void respond(ServerWebSocket socket, JsonObject payload){
    if(activeInvite == null || !activeInvite.invited().contains(socket)) return;

    boolean accepted = payload.getBoolean("accepted", false);
    activeInvite.responses().put(socket, accepted);

    LobbyStatus newStatus = accepted ? LobbyStatus.READY : LobbyStatus.DECLINED;
    players.computeIfPresent(socket, (s, player) -> player.withStatus(newStatus));

    broadcastLobbyState();

    boolean allResponded = activeInvite.responses().size() == activeInvite.invited().size();
    boolean enoughReady = countReadySockets() >= MIN_READY_PLAYER;

    if (allResponded || enoughReady) {
    vertx.cancelTimer(activeInvite.timerId());
    resolveInvite();
    }
  }

  private int countReadySockets(){
    long readyCount = 1 + activeInvite.responses().values().stream()
      .filter(accepted -> accepted)
      .count();

      return (int) readyCount;
  }

  private synchronized void resolveInvite(){
    if(activeInvite == null) return;

    Set<ServerWebSocket> readySockets = ConcurrentHashMap.newKeySet();
    readySockets.add(activeInvite.initiator());

    activeInvite.responses().forEach((socket, accepted) -> {
      if(accepted) readySockets.add(socket);
    });

    if(MIN_READY_PLAYER <= readySockets.size()){
      gameInProgress = true;
      JsonArray readyUsernames = new JsonArray(
      readySockets.stream().map(s -> players.get(s).username()).toList()
      );

      playingUsernames = readySockets.stream()
      .map(s -> players.get(s).username())
      .collect(Collectors.toSet());

      gameService.startGame(playingUsernames);

      JsonObject startedMessage = new JsonObject()
        .put("type", "game-started")
        .put("payload", new JsonObject().put("players", readyUsernames));

        for(var socket : readySockets){
          socket.writeTextMessage(startedMessage.encode());
        }
    } else {
      JsonObject cancelledMessage = new JsonObject().put("type", "game-cancelled");
      activeInvite.initiator().writeTextMessage(cancelledMessage.encode());
      for (var socket : activeInvite.invited()) {
        socket.writeTextMessage(cancelledMessage.encode());
      }
    }
    
      for (var socket : activeInvite.invited()) {
        players.computeIfPresent(socket, (s, player) -> player.withStatus(LobbyStatus.IDLE));
      }

      activeInvite = null;
      broadcastLobbyState();
    }


    private void handleLeftPlayer(ServerWebSocket socket){
      if(activeInvite != null && (activeInvite.initiator().equals(socket) || activeInvite.invited().contains(socket))){
        vertx.cancelTimer(activeInvite.timerId());
        resolveInvite();
      }
    }

    private void sendError(ServerWebSocket socket, String message){
      socket.writeTextMessage(new JsonObject()
      .put("type", "error")
      .put("payload", new JsonObject().put("message", message))
      .encode());
    }

   private void handleConnection(ServerWebSocket socket, String username, String role) {
    // Remove any stale connection(s) for this username first — e.g. a fast
    // reconnect where the old socket's close event hasn't been processed yet.
    players.entrySet().removeIf(entry -> {
      boolean isSameUser = entry.getValue().username().equals(username);
      boolean isDifferentSocket = !entry.getKey().equals(socket);
      if (isSameUser && isDifferentSocket) {
        entry.getKey().close();
      }
      return isSameUser && isDifferentSocket;
    });

    players.put(socket, LobbyPlayer.connected(username, role));
    broadcastLobbyState();

    socket.textMessageHandler(message -> handleCommand(socket, message));

    socket.closeHandler(unused -> {
      players.remove(socket);
      handleLeftPlayer(socket);
      broadcastLobbyState();
    });
  }

  private void broadcastLobbyState() {
    JsonArray playerList = new JsonArray(
      players.values().stream().map(JsonObject::mapFrom).toList()
    );

    JsonObject message = new JsonObject()
    .put("type", "lobby-update")
    .put("payload", new JsonObject()
      .put("players", playerList)
      .put("gameInProgress", gameInProgress) 
      .put("playingUsernames", new ArrayList<>(playingUsernames))  
    );

    for (var socket : players.keySet()) {
      if (!socket.isClosed()) {
        socket.writeTextMessage(message.encode());
      }
    }
  }

  private void checkAnticipation(RoutingContext context){
    User jwtUser = context.user(); // wird vom JWTAuthHandler gesetzt 
    String username = jwtUser.principal().getString("sub");

    boolean isParticipant =gameInProgress && playingUsernames.contains(username);

    context.response()
      .putHeader("content-type", "application/json")
      .end(new JsonObject().put("isParticipant", isParticipant).encode());
  }
}