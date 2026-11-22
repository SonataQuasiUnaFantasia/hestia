package dev.hestia.web.controller;

import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.form.UtilisateurForm;
import dev.hestia.web.security.UtilisateurPrincipal;
import dev.hestia.web.service.UtilisateurException;
import dev.hestia.web.service.UtilisateurService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Réservé au rôle ADMIN (voir SecurityConfig : /admin/**). */
@Controller
@RequestMapping("/admin/utilisateurs")
@RequiredArgsConstructor
public class UtilisateurAdminController {

    private static final String VUE_FORMULAIRE = "admin/utilisateurs/formulaire";

    private final UtilisateurService utilisateurService;

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("utilisateurs", utilisateurService.lister());
        return "admin/utilisateurs/liste";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("utilisateur", utilisateurService.trouver(id));
        return "admin/utilisateurs/detail";
    }

    @GetMapping("/nouveau")
    public String nouveau(Model model) {
        UtilisateurForm form = new UtilisateurForm();
        form.setRole(Role.USER);
        return afficherFormulaire(model, form, null);
    }

    @PostMapping
    public String creer(@Valid UtilisateurForm form, BindingResult result, Model model,
                        RedirectAttributes redirect) {
        if (!result.hasErrors()) {
            try {
                Utilisateur cree = utilisateurService.creer(form);
                redirect.addFlashAttribute("message", "Utilisateur créé");
                return "redirect:/admin/utilisateurs/" + cree.getId();
            } catch (UtilisateurException e) {
                ajouterErreur(result, e);
            }
        }
        return afficherFormulaire(model, form, null);
    }

    @GetMapping("/{id}/modifier")
    public String modifier(@PathVariable Long id, Model model) {
        Utilisateur u = utilisateurService.trouver(id);
        UtilisateurForm form = new UtilisateurForm();
        form.setNom(u.getNom());
        form.setEmail(u.getEmail());
        form.setRole(u.getRole());
        return afficherFormulaire(model, form, id);
    }

    @PostMapping("/{id}")
    public String mettreAJour(@PathVariable Long id, @Valid UtilisateurForm form, BindingResult result,
                              Model model, RedirectAttributes redirect,
                              @AuthenticationPrincipal UtilisateurPrincipal courant) {
        if (!result.hasErrors()) {
            try {
                utilisateurService.modifier(id, form, courant.getId());
                redirect.addFlashAttribute("message", "Utilisateur modifié");
                return "redirect:/admin/utilisateurs/" + id;
            } catch (UtilisateurException e) {
                ajouterErreur(result, e);
            }
        }
        return afficherFormulaire(model, form, id);
    }

    @PostMapping("/{id}/supprimer")
    public String supprimer(@PathVariable Long id, RedirectAttributes redirect,
                            @AuthenticationPrincipal UtilisateurPrincipal courant) {
        try {
            utilisateurService.supprimer(id, courant.getId());
            redirect.addFlashAttribute("message", "Utilisateur supprimé");
        } catch (UtilisateurException e) {
            redirect.addFlashAttribute("erreur", e.getMessage());
        }
        return "redirect:/admin/utilisateurs";
    }

    private String afficherFormulaire(Model model, UtilisateurForm form, Long id) {
        form.setMotDePasse(null);
        model.addAttribute("utilisateurForm", form);
        model.addAttribute("id", id);
        model.addAttribute("roles", Role.values());
        return VUE_FORMULAIRE;
    }

    private void ajouterErreur(BindingResult result, UtilisateurException e) {
        if (e.getChamp() == null) {
            result.reject("utilisateur.erreur", e.getMessage());
        } else {
            result.rejectValue(e.getChamp(), "utilisateur.erreur", e.getMessage());
        }
    }
}
