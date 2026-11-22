package dev.hestia.web.controller;

import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.security.UtilisateurPrincipal;
import dev.hestia.web.service.UtilisateurService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class AccueilController {

    private final UtilisateurService utilisateurService;

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String accueil(@AuthenticationPrincipal UtilisateurPrincipal principal, Model model) {
        model.addAttribute("utilisateur", utilisateurService.trouver(principal.getId()));
        return "accueil";
    }

    @GetMapping("/profil")
    public String profil(@AuthenticationPrincipal UtilisateurPrincipal principal, Model model) {
        Utilisateur utilisateur = utilisateurService.trouver(principal.getId());
        model.addAttribute("utilisateur", utilisateur);
        return "profil";
    }
}
