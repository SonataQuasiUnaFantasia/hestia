package dev.hestia.web.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Photo publiée. Les octets sont sur disque ; la base ne porte que des références relatives
 * (noms générés par le serveur) et les dimensions de la version d'affichage.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "PHOTO")
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auteur_id", nullable = false, updatable = false)
    private Utilisateur auteur;

    /** Fichier original, octets reçus inchangés (répertoire des originaux). */
    @Column(name = "reference_originale", nullable = false, updatable = false, length = 64)
    private String referenceOriginale;

    /** Version d'affichage, toujours un JPEG (répertoire des versions optimisées). */
    @Column(name = "reference_optimisee", nullable = false, updatable = false, length = 64)
    private String referenceOptimisee;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "format_original", nullable = false, updatable = false, length = 10)
    private FormatImage formatOriginal;

    /** Largeur en pixels de la version optimisée. */
    @Column(name = "largeur", nullable = false, updatable = false)
    private int largeur;

    /** Hauteur en pixels de la version optimisée. */
    @Column(name = "hauteur", nullable = false, updatable = false)
    private int hauteur;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private LocalDateTime dateCreation;
}
