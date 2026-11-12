package de.thm.codecracker.game;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

// Stellt den öffentlichen REST-Endpunkt zum Abrufen des Highscores bereit.
public class HighscoreController{
    private HighscoreRepository repository;

    public HighscoreController(HighscoreRepository repository){
        this.repository = repository;
    }

    public void registerRoutes(Router router){
        router.get("/api/highscore").handler(this::getHighscore);
    }

    /**
   * Behandelt GET /api/highscore: liefert die Top-5-Rangliste der
   * letzten zehn Tage als JSON-Array von {@code { username, score }}-Objekten.
   */
    public void getHighscore(RoutingContext context){
        var entries = repository.topFiveLastTenDays().await();
        var json = new JsonArray();
        for(var entry : entries){
            json.add(new JsonObject().put("username", entry.username()).put("score", entry.totalScore()));
        }
        context.response().putHeader("content-type", "application/json").end(json.encode());
    }
}