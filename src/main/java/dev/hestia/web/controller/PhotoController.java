package dev.hestia.web.controller;

import dev.hestia.web.entity.Photo;
import dev.hestia.web.security.UtilisateurPrincipal;
import dev.hestia.web.service.ImageRefuseeException;
import dev.hestia.web.service.PhotoService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.util.unit.DataSize;

/** Accessible à tout utilisateur authentifié (voir SecurityConfig) ; les droits de suppression sont vérifiés par le service. */
@Controller
@RequestMapping("/photos")
public class PhotoController {

    private final PhotoService photoService;
    /** Taille maximale d'une image, affichée et appliquée par le script avant l'envoi. */
    private final DataSize tailleMax;

    public PhotoController(PhotoService photoService, @Value("${app.upload.max-file-size}") DataSize tailleMax) {
        this.photoService = photoService;
        this.tailleMax = tailleMax;
    }

    @GetMapping
    public String liste(@RequestParam(defaultValue = "0") int page,
                        @AuthenticationPrincipal UtilisateurPrincipal courant, Model model) {
        model.addAttribute("photos", photoService.lister(courant.getId(), page));
        return "photos/liste";
    }

    /** Page d'envoi par lot : le script de la page envoie les images une par une à {@link #publier}. */
    @GetMapping("/envoi")
    public String envoi(Model model) {
        model.addAttribute("tailleMax", tailleMax.toBytes());
        return "photos/envoi";
    }

    /** Une image par requête ; réponse JSON lue par le script d'envoi. */
    @PostMapping
    public ResponseEntity<Map<String, Long>> publier(@RequestParam(name = "image", required = false) MultipartFile image,
                                                     @AuthenticationPrincipal UtilisateurPrincipal courant) {
        Photo photo = photoService.publier(courant.getId(), image);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", photo.getId()));
    }

    @PostMapping("/{id}/supprimer")
    public String supprimer(@PathVariable Long id, RedirectAttributes redirect,
                            @AuthenticationPrincipal UtilisateurPrincipal courant) {
        photoService.supprimer(id, courant.getId());
        redirect.addFlashAttribute("message", "Photo supprimée");
        return "redirect:/photos";
    }

    /** Sert uniquement la version optimisée, toujours un JPEG produit par le serveur. */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(photoService.lireImageOptimisee(id));
    }

    @ExceptionHandler(ImageRefuseeException.class)
    ResponseEntity<Map<String, String>> imageRefusee(ImageRefuseeException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("erreur", e.getMessage()));
    }
}
