package dev.hestia.web.service;

import lombok.Getter;

/** Violation d'une règle métier ; {@code champ} est null pour une erreur globale. */
@Getter
public class UtilisateurException extends RuntimeException {

    private final String champ;

    public UtilisateurException(String champ, String message) {
        super(message);
        this.champ = champ;
    }
}
