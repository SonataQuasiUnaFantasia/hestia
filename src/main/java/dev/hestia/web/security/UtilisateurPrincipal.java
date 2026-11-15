package dev.hestia.web.security;

import java.util.Collection;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Utilisateur authentifié ; conserve l'id pour ne pas dépendre de l'email (modifiable). */
@Getter
public class UtilisateurPrincipal extends User {

    private final Long id;

    public UtilisateurPrincipal(Long id, String email, String motDePasse, boolean actif,
                                Collection<? extends GrantedAuthority> authorities) {
        super(email, motDePasse, actif, true, true, true, authorities);
        this.id = id;
    }
}
