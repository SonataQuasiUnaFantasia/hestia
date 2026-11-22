package dev.hestia.web.dto;

import java.time.LocalDateTime;

/**
 * Photo prête à l'affichage (version optimisée).
 *
 * @param supprimable true si l'utilisateur connecté a le droit de supprimer cette photo
 */
public record PhotoVue(Long id, String auteur, LocalDateTime dateCreation, int largeur, int hauteur,
                       boolean supprimable) {
}
