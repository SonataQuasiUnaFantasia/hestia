package dev.hestia.web.dto;

/**
 * Références de stockage des deux fichiers d'une image : noms générés par le serveur,
 * relatifs aux répertoires des originaux et des versions optimisées.
 */
public record FichiersImage(String referenceOriginale, String referenceOptimisee) {
}
