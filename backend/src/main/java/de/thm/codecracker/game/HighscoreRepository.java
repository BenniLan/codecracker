package de.thm.codecracker.game;

import java.util.ArrayList;
import java.util.List;

import de.thm.codecracker.game.model.HighscoreEntry;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;


public class HighscoreRepository{
    private final Pool pool;

    public HighscoreRepository(Pool pool) {
        this.pool = pool;
    }

    public Future<List<HighscoreEntry>> topFiveLastTenDays(){
        String sql = """
        SELECT u.username, GREATEST(SUM(gp.score), 0) AS total_score
        FROM game_players gp
        JOIN users u ON u.id = gp.user_id
        JOIN games g on g.id = gp.game_id
        WHERE g.ended_at >= NOW() - INTERVAL 10 DAY
        GROUP BY u.id, u.username
        ORDER BY total_score DESC
        LIMIT 5
        """;

         return pool.query(sql).execute().map(rows -> toList(rows));
    }

    private List<HighscoreEntry> toList(RowSet<Row> rows){
        List<HighscoreEntry> entries = new ArrayList<>();
        for(Row row : rows){
            entries.add(new HighscoreEntry(row.getString("username"), row.getInteger("total_score")));
        }
        return entries;
    }

    public Future<Integer> totalScoreForUser(long userId) {
    String sql = """
      SELECT GREATEST(SUM(gp.score), 0) AS total_score
      FROM game_players gp
      JOIN games g ON g.id = gp.game_id
      WHERE gp.user_id = ? AND g.ended_at >= NOW() - INTERVAL 10 DAY
      """;
 
    return pool.preparedQuery(sql)
      .execute(Tuple.of(userId))
      .map(rows -> {
        var iterator = rows.iterator();
        Row row = iterator.hasNext() ? iterator.next() : null;
        Integer total = row == null ? null : row.getInteger("total_score");
        return total == null ? 0 : total;
      });
  }
}
