package de.thm.codecracker.user.model;   

public record User(
    Long id,
    String username,
    String passwordHash,   
    String role
) { }