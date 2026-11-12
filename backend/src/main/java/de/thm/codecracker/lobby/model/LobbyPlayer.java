package de.thm.codecracker.lobby.model;

public record LobbyPlayer(
    String username,
    String role,
    LobbyStatus status
){
    public static LobbyPlayer connected(String username, String role){
        return new LobbyPlayer(username, role, LobbyStatus.IDLE);
    }

    public LobbyPlayer withStatus(LobbyStatus newStatus){
        return new LobbyPlayer(username, role, newStatus);
    }
}