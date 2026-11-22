package dev.hestia.web.repository;

import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, Long> {

    Optional<Utilisateur> findByEmailIgnoreCase(String email);

    boolean existsByRole(Role role);
}
