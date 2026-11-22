package dev.hestia.web.service;

import dev.hestia.web.dto.FichiersImage;
import dev.hestia.web.dto.PhotoVue;
import dev.hestia.web.entity.Photo;
import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.repository.PhotoRepository;
import dev.hestia.web.repository.UtilisateurRepository;
import dev.hestia.web.service.TraitementImageService.ImageTraitee;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoService {

    private static final int TAILLE_PAGE = 24;

    private final PhotoRepository photoRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final TraitementImageService traitement;
    private final StockageImages stockage;
    private final TransactionTemplate transactions;

    /**
     * Publie une photo pour l'utilisateur connecté. Un envoi par lot est une suite d'appels
     * indépendants : chaque image est acceptée ou refusée seule.
     *
     * <p>L'image est validée et optimisée en mémoire avant toute écriture ; un refus ne laisse
     * aucune trace. Si l'enregistrement en base échoue, les deux fichiers écrits sont effacés.
     *
     * @param idAuteur identifiant de l'utilisateur authentifié (jamais une valeur fournie par le client)
     * @throws ImageRefuseeException si le fichier est absent ou refusé (message destiné à l'utilisateur)
     */
    public Photo publier(Long idAuteur, MultipartFile fichier) {
        if (fichier == null || fichier.isEmpty()) {
            throw new ImageRefuseeException("aucune image reçue");
        }
        if (!utilisateurRepository.existsById(idAuteur)) {
            throw new AccessDeniedException("Utilisateur inconnu");
        }

        byte[] octets = lire(fichier);
        ImageTraitee traitee = traitement.traiter(octets);
        FichiersImage fichiers = stockage.ecrire(octets, traitee.format(), traitee.optimisee());
        try {
            Photo photo = new Photo();
            photo.setAuteur(utilisateurRepository.getReferenceById(idAuteur));
            photo.setReferenceOriginale(fichiers.referenceOriginale());
            photo.setReferenceOptimisee(fichiers.referenceOptimisee());
            photo.setFormatOriginal(traitee.format());
            photo.setLargeur(traitee.largeur());
            photo.setHauteur(traitee.hauteur());
            photo.setDateCreation(LocalDateTime.now());
            return photoRepository.save(photo);
        } catch (RuntimeException | Error e) {
            stockage.supprimer(fichiers);
            throw e;
        }
    }

    /** Galerie, de la photo la plus récente à la plus ancienne. */
    @Transactional(readOnly = true)
    public Slice<PhotoVue> lister(Long idConnecte, int page) {
        Utilisateur connecte = utilisateurConnecte(idConnecte);
        return photoRepository
                .findAllByOrderByDateCreationDescIdDesc(PageRequest.of(Math.max(page, 0), TAILLE_PAGE))
                .map(photo -> new PhotoVue(photo.getId(), photo.getAuteur().getNom(), photo.getDateCreation(),
                        photo.getLargeur(), photo.getHauteur(), peutSupprimer(photo, connecte)));
    }

    /**
     * Supprime une photo si l'utilisateur connecté en est l'auteur ou est administrateur.
     * La ligne disparaît de la base, puis ses fichiers sont effacés ; un fichier qui ne peut pas
     * l'être est signalé dans les journaux, sans faire échouer la suppression.
     *
     * @throws AccessDeniedException si l'utilisateur n'a pas le droit de supprimer cette photo
     */
    public void supprimer(Long idPhoto, Long idConnecte) {
        FichiersImage aEffacer = transactions.execute(statut -> {
            Utilisateur connecte = utilisateurConnecte(idConnecte);
            Photo photo = photoRepository.findById(idPhoto)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (!peutSupprimer(photo, connecte)) {
                throw new AccessDeniedException("Suppression non autorisée");
            }
            photoRepository.delete(photo);
            return new FichiersImage(photo.getReferenceOriginale(), photo.getReferenceOptimisee());
        });
        stockage.supprimer(aEffacer);
    }

    /**
     * Version optimisée d'une photo, retrouvée par son identifiant en base : le client ne fournit
     * jamais de chemin ni de nom de fichier.
     */
    @Transactional(readOnly = true)
    public byte[] lireImageOptimisee(Long idPhoto) {
        Photo photo = photoRepository.findById(idPhoto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return stockage.lireOptimisee(photo.getReferenceOptimisee()).orElseThrow(() -> {
            log.error("Fichier optimisé absent pour la photo {}", idPhoto);
            return new ResponseStatusException(HttpStatus.NOT_FOUND);
        });
    }

    /** Règle unique de suppression : l'auteur pour ses photos, un administrateur pour toutes. */
    static boolean peutSupprimer(Photo photo, Utilisateur utilisateur) {
        return utilisateur.getRole() == Role.ADMIN || photo.getAuteur().getId().equals(utilisateur.getId());
    }

    /** Le rôle est relu en base à chaque requête : il ne dépend ni du client ni d'une session ancienne. */
    private Utilisateur utilisateurConnecte(Long idConnecte) {
        return utilisateurRepository.findById(idConnecte)
                .orElseThrow(() -> new AccessDeniedException("Utilisateur inconnu"));
    }

    private static byte[] lire(MultipartFile fichier) {
        try {
            return fichier.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du fichier envoyé impossible", e);
        }
    }
}
