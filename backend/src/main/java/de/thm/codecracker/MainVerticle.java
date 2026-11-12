package de.thm.codecracker;

import de.thm.codecracker.config.AppConfig;
import de.thm.codecracker.config.DatabaseConfig;
import de.thm.codecracker.game.GameRepository;
import de.thm.codecracker.game.GameService;
import de.thm.codecracker.game.GameWebSocketController;
import de.thm.codecracker.game.HighscoreController;
import de.thm.codecracker.game.HighscoreRepository;
import de.thm.codecracker.lobby.LobbyWebSocketController;

import io.vertx.core.AbstractVerticle;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.LoggerFormat;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.StaticHandler;
import de.thm.codecracker.user.AuthController;
import de.thm.codecracker.user.UserRepository;
import de.thm.codecracker.user.UserService;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import de.thm.codecracker.user.UserWebSocketController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainVerticle extends AbstractVerticle {
  private static final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);
  public void start() {
  var config = AppConfig.fromEnvironment();

  var pool = DatabaseConfig.createPool(vertx, config);
  String secret = System.getenv("JWT_SECRET");
  if (secret==null || secret.isBlank()) {
    throw new IllegalStateException("JWT_SECRET is not  set");
  }
  JWTAuthOptions jwtconfig = new JWTAuthOptions()
    .addPubSecKey(new PubSecKeyOptions()
      .setAlgorithm("HS256")
      .setBuffer("keyboardcat"));
  var jwtAuth = JWTAuth.create(vertx, jwtconfig);



  var userRepository = new UserRepository(pool);
  var userService = new UserService(userRepository, vertx);
  var userWebSocketController = new UserWebSocketController(vertx, userService, jwtAuth);
  var authController = new AuthController(userService, jwtAuth);

  
  var gameRepository = new GameRepository(pool);
  var highscoreRepository = new HighscoreRepository(pool);
  var gameService = new GameService(vertx, userRepository, gameRepository, highscoreRepository);
  var highscoreController = new HighscoreController(highscoreRepository);

  
  var lobbyWebSocketController = new LobbyWebSocketController(jwtAuth, vertx, gameService);
  gameService.setOnGameEnded(lobbyWebSocketController::markGameEnded);

  
  var gameWebSocketController = new GameWebSocketController(jwtAuth, gameService);

  
  Router router = Router.router(vertx);
  router.route().handler(LoggerHandler.create(LoggerFormat.DEFAULT));
  router.route().handler(BodyHandler.create());
 
  router.route("/api/game/*").handler(JWTAuthHandler.create(jwtAuth));
  router.route("/api/highscore").handler(JWTAuthHandler.create(jwtAuth)); // NEW

 
  userWebSocketController.registerRoutes(router);
  userWebSocketController.registerEventBusConsumer();
  authController.registerRoutes(router);
  lobbyWebSocketController.registerRoutes(router);
  gameWebSocketController.registerRoutes(router);   // NEW
  highscoreController.registerRoutes(router);        // NEW

  router.get("/").handler(ctx ->
    ctx.response()
      .setStatusCode(302)
      .putHeader("Location", "/index.html")
      .end()
  );
  
  

    router.route().handler(StaticHandler.create("webroot"));
    
    var server = vertx.createHttpServer();
    server.requestHandler(router);
    server.listen(config.httpPort()).await();

    LOGGER.info("Server started on port {}", config.httpPort());
  }
}
