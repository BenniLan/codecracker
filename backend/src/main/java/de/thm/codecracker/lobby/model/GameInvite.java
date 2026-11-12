package de.thm.codecracker.lobby.model;

import io.vertx.core.http.ServerWebSocket;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public record GameInvite(
    ServerWebSocket initiator,
    Set<ServerWebSocket> invited,
    long timerId,
    Map<ServerWebSocket, Boolean> responses
) {
    public static GameInvite create(ServerWebSocket initiator, Set<ServerWebSocket> invited, long timerId){
        return new GameInvite(initiator, invited, timerId, new ConcurrentHashMap<>());
    }
}
