INSERT INTO games (id, status, secret_code, round_limit, ended_at)
VALUES (1, 'FINISHED', 'RED,GREEN,BLUE,YELLOW', 10, NOW());

INSERT INTO game_players (game_id, user_id, score)
VALUES (1, 1, 3);  -- user_id 1 hat gewonnen (3 Punkte)