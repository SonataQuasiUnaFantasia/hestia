package dev.hestia.web.security;

import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.repository.UtilisateurRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UtilisateurDetailsService implements UserDetailsService {

    private final UtilisateurRepository repository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Utilisateur u = repository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur introuvable"));
        boolean aUnMotDePasse = u.getMotDePasse() != null && !u.getMotDePasse().isEmpty();
        return new UtilisateurPrincipal(
                u.getId(),
                u.getEmail(),
                aUnMotDePasse ? u.getMotDePasse() : "",
                aUnMotDePasse,
                List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
    }
}
