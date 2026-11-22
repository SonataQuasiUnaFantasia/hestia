package dev.hestia.web.service;

import dev.hestia.web.dto.FichiersImage;
import dev.hestia.web.entity.FormatImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stockage des images sur le système de fichiers, sous le répertoire configuré :
 * {@code originals/} (octets reçus, inchangés) et {@code optimized/} (JPEG d'affichage).
 *
 * <p>Aucun chemin ne provient du client : les fichiers sont nommés par le serveur (UUID) et toute
 * référence est validée par un motif strict avant d'être résolue dans son répertoire.
 */
@Slf4j
@Component
public class StockageImages {

    private static final String UUID_MOTIF = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern REFERENCE = Pattern.compile(UUID_MOTIF + "\\.(jpg|png|webp)");
    private static final String EXTENSION_OPTIMISEE = "jpg";

    private final Path originaux;
    private final Path optimisees;

    public StockageImages(@Value("${app.upload.directory}") Path repertoire) {
        try {
            Path racine = repertoire.toAbsolutePath().normalize();
            Files.createDirectories(racine);
            racine = racine.toRealPath();
            this.originaux = preparerRepertoire(racine, "originals");
            this.optimisees = preparerRepertoire(racine, "optimized");
        } catch (IOException e) {
            throw new UncheckedIOException("Répertoire de stockage des images inutilisable : "
                    + repertoire, e);
        }
        log.info("Stockage des photos : {}", originaux.getParent());
    }

    private static Path preparerRepertoire(Path racine, String nom) throws IOException {
        Path repertoire = racine.resolve(nom);
        if (Files.notExists(repertoire, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(repertoire);
        }
        // Un lien symbolique à la place d'un sous-répertoire détournerait toutes les opérations.
        if (!Files.isDirectory(repertoire, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(repertoire + " n'est pas un répertoire réel");
        }
        if (!Files.isWritable(repertoire) || !Files.isReadable(repertoire)) {
            throw new IOException(repertoire + " n'est pas accessible en lecture et en écriture");
        }
        return repertoire;
    }

    /**
     * Écrit l'original (octets reçus, tels quels) et sa version optimisée sous un nouvel identifiant.
     * Si l'une des deux écritures échoue, ce qui a déjà été écrit est effacé.
     */
    public FichiersImage ecrire(byte[] original, FormatImage format, byte[] optimisee) {
        String identifiant = UUID.randomUUID().toString();
        FichiersImage fichiers = new FichiersImage(
                identifiant + "." + format.getExtension(), identifiant + "." + EXTENSION_OPTIMISEE);
        try {
            ecrire(resoudre(originaux, fichiers.referenceOriginale()), original);
            ecrire(resoudre(optimisees, fichiers.referenceOptimisee()), optimisee);
        } catch (IOException e) {
            supprimer(fichiers);
            throw new UncheckedIOException("Écriture des fichiers de l'image impossible", e);
        }
        return fichiers;
    }

    /** Efface les deux fichiers de cette image, et eux seuls ; sans effet s'ils n'existent plus. Ne lève jamais d'erreur. */
    public void supprimer(FichiersImage fichiers) {
        effacer(originaux, fichiers.referenceOriginale());
        effacer(optimisees, fichiers.referenceOptimisee());
    }

    /** @return les octets de la version optimisée, ou vide si le fichier n'existe pas */
    public Optional<byte[]> lireOptimisee(String reference) {
        Path chemin = resoudre(optimisees, reference);
        if (!Files.isRegularFile(chemin, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (InputStream flux = Files.newInputStream(chemin, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(flux.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture de l'image impossible", e);
        }
    }

    /**
     * Chemin physique d'une référence : motif strict (ni séparateur, ni « .. », ni chemin absolu),
     * puis contrôle que le chemin résolu est bien un enfant direct du répertoire attendu.
     */
    private static Path resoudre(Path repertoire, String reference) {
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("Référence de fichier invalide");
        }
        Path chemin = repertoire.resolve(reference).normalize();
        if (!repertoire.equals(chemin.getParent())) {
            throw new IllegalArgumentException("Référence de fichier hors du stockage");
        }
        return chemin;
    }

    private static void ecrire(Path chemin, byte[] octets) throws IOException {
        // CREATE_NEW : échoue si le nom existe déjà (y compris sous forme de lien symbolique).
        try (OutputStream sortie = Files.newOutputStream(chemin,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            sortie.write(octets);
        }
        if (Files.size(chemin) != octets.length) {
            throw new IOException("Écriture incomplète de " + chemin.getFileName());
        }
    }

    private static void effacer(Path repertoire, String reference) {
        try {
            // Ne suit pas les liens symboliques : seul le nom lui-même est retiré.
            Files.deleteIfExists(resoudre(repertoire, reference));
        } catch (IOException | RuntimeException e) {
            log.warn("Fichier image {} non effacé (à supprimer manuellement) : {}", reference, e.toString());
        }
    }
}
