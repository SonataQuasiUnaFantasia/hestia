package dev.hestia.web.security;

import dev.hestia.web.service.UtilisateurService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Crée le premier administrateur au démarrage si app.admin.email et app.admin.password sont fournis
 * (variables APP_ADMIN_EMAIL / APP_ADMIN_PASSWORD) et si aucun administrateur n'existe encore.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PremierAdminInitializer implements ApplicationRunner {

    private final UtilisateurService utilisateurService;

    @Value("${app.admin.email:}")
    private String email;

    @Value("${app.admin.nom:Administrateur}")
    private String nom;

    @Value("${app.admin.password:}")
    private String motDePasse;

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || motDePasse.isBlank()) {
            return;
        }
        if (motDePasse.length() < 8 || motDePasse.length() > 72) {
            log.warn("APP_ADMIN_PASSWORD ignoré : il doit contenir entre 8 et 72 caractères.");
            return;
        }
        if (utilisateurService.initialiserPremierAdmin(email, nom, motDePasse)) {
            log.info("Premier administrateur créé : {}", email.trim().toLowerCase());
        }
    }
}
