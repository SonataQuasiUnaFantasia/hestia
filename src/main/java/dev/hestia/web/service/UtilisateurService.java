package dev.hestia.web.service;

import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.form.UtilisateurForm;
import dev.hestia.web.repository.UtilisateurRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UtilisateurService {

    private final UtilisateurRepository repository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<Utilisateur> lister() {
        return repository.findAll(Sort.by("nom"));
    }

    @Transactional(readOnly = true)
    public Utilisateur trouver(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Utilisateur creer(UtilisateurForm form) {
        if (form.getMotDePasse() == null || form.getMotDePasse().isEmpty()) {
            throw new UtilisateurException("motDePasse", "Le mot de passe est obligatoire");
        }
        String email = normaliser(form.getEmail());
        verifierEmailLibre(email, null);

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setNom(form.getNom().trim());
        utilisateur.setEmail(email);
        utilisateur.setRole(form.getRole());
        utilisateur.setMotDePasse(passwordEncoder.encode(form.getMotDePasse()));
        return repository.save(utilisateur);
    }

    @Transactional
    public Utilisateur modifier(Long id, UtilisateurForm form, Long idConnecte) {
        Utilisateur utilisateur = trouver(id);
        if (id.equals(idConnecte) && form.getRole() != utilisateur.getRole()) {
            throw new UtilisateurException("role", "Vous ne pouvez pas modifier votre propre rôle");
        }
        String email = normaliser(form.getEmail());
        verifierEmailLibre(email, id);

        utilisateur.setNom(form.getNom().trim());
        utilisateur.setEmail(email);
        utilisateur.setRole(form.getRole());
        if (form.getMotDePasse() != null && !form.getMotDePasse().isEmpty()) {
            utilisateur.setMotDePasse(passwordEncoder.encode(form.getMotDePasse()));
        }
        return repository.save(utilisateur);
    }

    @Transactional
    public void supprimer(Long id, Long idConnecte) {
        if (id.equals(idConnecte)) {
            throw new UtilisateurException(null, "Vous ne pouvez pas supprimer votre propre compte");
        }
        Utilisateur utilisateur = trouver(id);
        try {
            repository.delete(utilisateur);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new UtilisateurException(null,
                    "Cet utilisateur ne peut pas être supprimé car des données lui sont rattachées");
        }
    }

    /**
     * Crée le premier administrateur, ou promeut le compte existant portant cet email.
     * Sans effet si un administrateur existe déjà : ne réinitialise jamais un compte.
     *
     * @return true si un administrateur a été créé ou promu
     */
    @Transactional
    public boolean initialiserPremierAdmin(String email, String nom, String motDePasse) {
        if (repository.existsByRole(Role.ADMIN)) {
            return false;
        }
        Utilisateur utilisateur = repository.findByEmailIgnoreCase(normaliser(email))
                .orElseGet(Utilisateur::new);
        utilisateur.setEmail(normaliser(email));
        if (utilisateur.getNom() == null) {
            utilisateur.setNom(nom);
        }
        utilisateur.setRole(Role.ADMIN);
        utilisateur.setMotDePasse(passwordEncoder.encode(motDePasse));
        repository.save(utilisateur);
        return true;
    }

    private void verifierEmailLibre(String email, Long idCourant) {
        repository.findByEmailIgnoreCase(email)
                .filter(autre -> !autre.getId().equals(idCourant))
                .ifPresent(autre -> {
                    throw new UtilisateurException("email", "Cet email est déjà utilisé");
                });
    }

    private String normaliser(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
