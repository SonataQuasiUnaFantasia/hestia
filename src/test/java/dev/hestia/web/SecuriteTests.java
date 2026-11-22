package dev.hestia.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.repository.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecuriteTests {

	@Autowired MockMvc mvc;
	@Autowired UtilisateurRepository repository;
	@Autowired PasswordEncoder encoder;

	@BeforeEach
	void creerComptes() {
		creer("admin@test.fr", "Admin", Role.ADMIN, "motdepasse-admin");
		creer("user@test.fr", "User", Role.USER, "motdepasse-user");
		creer("sans-mdp@test.fr", "Sans", Role.USER, null);
	}

	private void creer(String email, String nom, Role role, String motDePasse) {
		Utilisateur u = new Utilisateur();
		u.setEmail(email);
		u.setNom(nom);
		u.setRole(role);
		u.setMotDePasse(motDePasse == null ? null : encoder.encode(motDePasse));
		repository.save(u);
	}

	@Test
	void anonymeEstRedirigeVersLogin() throws Exception {
		mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/admin/utilisateurs")).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
		mvc.perform(get("/login")).andExpect(status().isOk());
	}

	@Test
	void adminEtUserPeuventSeConnecter() throws Exception {
		mvc.perform(formLogin("/login").user("email", "admin@test.fr").password("motDePasse", "motdepasse-admin"))
				.andExpect(authenticated());
		mvc.perform(formLogin("/login").user("email", "USER@test.fr").password("motDePasse", "motdepasse-user"))
				.andExpect(authenticated());
	}

	@Test
	void mauvaisIdentifiantsRefuses() throws Exception {
		mvc.perform(formLogin("/login").user("email", "user@test.fr").password("motDePasse", "faux"))
				.andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));
		mvc.perform(formLogin("/login").user("email", "inconnu@test.fr").password("motDePasse", "faux"))
				.andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void compteSansMotDePasseNePeutPasSeConnecter() throws Exception {
		mvc.perform(formLogin("/login").user("email", "sans-mdp@test.fr").password("motDePasse", ""))
				.andExpect(unauthenticated());
	}

	@Test
	void motDePasseStockeHache() {
		Utilisateur u = repository.findByEmailIgnoreCase("user@test.fr").orElseThrow();
		org.assertj.core.api.Assertions.assertThat(u.getMotDePasse()).isNotEqualTo("motdepasse-user")
				.startsWith("$2");
	}

	@Test
	void postSansCsrfRefuse() throws Exception {
		mvc.perform(post("/login").param("email", "user@test.fr").param("motDePasse", "motdepasse-user"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/logout")).andExpect(status().isForbidden());
	}

	@Test
	void deconnexionRedirigeVersLogin() throws Exception {
		mvc.perform(logout()).andExpect(redirectedUrl("/login?logout"));
	}

	@Test
	void userNAccedePasAuxRoutesAdmin() throws Exception {
		var user = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
				.user(principal("user@test.fr"));
		mvc.perform(get("/admin/utilisateurs").with(user)).andExpect(status().isForbidden());
		mvc.perform(get("/admin/utilisateurs/nouveau").with(user)).andExpect(status().isForbidden());
		mvc.perform(post("/admin/utilisateurs").with(user).with(csrf()).param("nom", "Pirate")
				.param("email", "pirate@test.fr").param("motDePasse", "motdepasse1").param("role", "ADMIN"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/admin/utilisateurs/1/supprimer").with(user).with(csrf())).andExpect(status().isForbidden());
		org.assertj.core.api.Assertions.assertThat(repository.findByEmailIgnoreCase("pirate@test.fr")).isEmpty();
		// ... mais accède à son accueil et à son profil
		mvc.perform(get("/").with(user)).andExpect(status().isOk());
		mvc.perform(get("/profil").with(user)).andExpect(status().isOk());
	}

	private dev.hestia.web.security.UtilisateurPrincipal principal(String email) {
		return (dev.hestia.web.security.UtilisateurPrincipal) new dev.hestia.web.security.UtilisateurDetailsService(repository)
				.loadUserByUsername(email);
	}
}
