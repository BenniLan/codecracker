package de.thm.codecracker.user;
import java.util.ArrayList;
import java.util.List;
import de.thm.codecracker.user.model.User;
import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;


public class UserRepository {
  private final Pool pool;

  public UserRepository(Pool pool) {
    this.pool = pool;
  }

  public Future<User> findByUsername(String username){
    String sql = """
            SELECT id, username, password_hash, role
            FROM users
            WHERE username = ?
            """;
            RowSet<Row> rows = pool.preparedQuery(sql)
      .execute(Tuple.of(username))
      .await();

    return Future.succeededFuture(firstOrNull(rows));
  }

  public Future<User> create(String username, String passwordHash, String role) {
    String sql = """
      INSERT INTO users (username, password_hash, role)
      VALUES (?, ?, ?)
      """;

    RowSet<Row> result = pool.preparedQuery(sql)
      .execute(Tuple.of(username, passwordHash, role))
      .await();

    Long id = result.property(MySQLClient.LAST_INSERTED_ID);
    User user = findById(id).await();

    return Future.succeededFuture(user);
  }

  public Future<User> findById(Long id) {
    String sql = """
      SELECT id, username, password_hash, role
      FROM users
      WHERE id = ?
      """;

    RowSet<Row> rows = pool.preparedQuery(sql)
      .execute(Tuple.of(id))
      .await();

    return Future.succeededFuture(firstOrNull(rows));
  }

  private User toUser(Row row) {
    return new User(
      row.getLong("id"),
      row.getString("username"),
      row.getString("password_hash"),
      row.getString("role")
    );
  }

  private User firstOrNull(RowSet<Row> rows) {
    RowIterator<Row> it = rows.iterator();
    return it.hasNext() ? toUser(it.next()) : null;
  }

  public Future<User> update(Long id, String username, String passwordHash, String role) {
  String sql = """
    UPDATE users
    SET username = ?, password_hash = ?, role = ?
    WHERE id = ?
    """;

  pool.preparedQuery(sql)
    .execute(Tuple.of(username, passwordHash, role, id))
    .await();

  User user = findById(id).await();
  return Future.succeededFuture(user);
}

  public Future<List<User>> findAll() {
    String sql = """
      SELECT id, username, password_hash, role
      FROM users
      ORDER BY id
      """;

    RowSet<Row> rows = pool.preparedQuery(sql)
      .execute()
      .await();

    return Future.succeededFuture(toList(rows));
  }

  private List<User> toList(RowSet<Row> rows) {
    List<User> users = new ArrayList<>();

    for (Row row : rows) {
      users.add(toUser(row));
    }

    return users;
  }

  public Future<Boolean> deleteById(Long id) {
    String sql = """
      DELETE FROM users
      WHERE id = ?
      """;

    RowSet<Row> result = pool.preparedQuery(sql)
      .execute(Tuple.of(id))
      .await();

    return Future.succeededFuture(result.rowCount() > 0);
  }
}