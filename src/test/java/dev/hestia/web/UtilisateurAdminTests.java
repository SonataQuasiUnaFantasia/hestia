package dev.hestia.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.repository.UtilisateurRepository;
import dev.hestia.web.security.UtilisateurDetailsService;
import dev.hestia.web.security.UtilisateurPrincipal;
import dev.hestia.web.service.UtilisateurService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UtilisateurAdminTests {

	@Autowired MockMvc mvc;
	@Autowired UtilisateurRepository repository;
	@Autowired PasswordEncoder encoder;
	@Autowired UtilisateurService service;
	@Autowired UtilisateurDetailsService detailsService;

	Utilisateur admin;

	@BeforeEach
	void creerAdmin() {
		admin = nouveau("admin@test.fr", "Admin", Role.ADMIN, "motdepasse-admin");
	}

	private Utilisateur nouveau(String email, String nom, Role role, String mdp) {
		Utilisateur u = new Utilisateur();
		u.setEmail(email);
		u.setNom(nom);
		u.setRole(role);
		u.setMotDePasse(encoder.encode(mdp));
		return repository.save(u);
	}

	private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
		return b.with(user((UtilisateurPrincipal) detailsService.loadUserByUsername("admin@test.fr"))).with(csrf());
	}

	@Test
	void listeEtDetail() throws Exception {
		mvc.perform(admin(get("/admin/utilisateurs"))).andExpect(status().isOk())
				.andExpect(view().name("admin/utilisateurs/liste"));
		mvc.perform(admin(get("/admin/utilisateurs/" + admin.getId()))).andExpect(status().isOk())
				.andExpect(view().name("admin/utilisateurs/detail"));
		mvc.perform(admin(get("/admin/utilisateurs/999999"))).andExpect(status().isNotFound());
	}

	@Test
	void creationValideAvecMotDePasseHache() throws Exception {
		mvc.perform(admin(post("/admin/utilisateurs")).param("nom", "Alice").param("email", "Alice@Test.fr")
				.param("motDePasse", "motdepasse1").param("role", "USER")).andExpect(status().is3xxRedirection());
		Utilisateur cree = repository.findByEmailIgnoreCase("alice@test.fr").orElseThrow();
		assertThat(cree.getRole()).isEqualTo(Role.USER);
		assertThat(cree.getEmail()).isEqualTo("alice@test.fr");
		assertThat(cree.getMotDePasse()).isNotEqualTo("motdepasse1");
		assertThat(encoder.matches("motdepasse1", cree.getMotDePasse())).isTrue();
	}

	@Test
	void creationInvalideReaffichePageAvecErreurs() throws Exception {
		mvc.perform(admin(post("/admin/utilisateurs")).param("nom", "A").param("email", "pas-un-email")
				.param("motDePasse", "court").param("role", "USER"))
				.andExpect(status().isOk()).andExpect(view().name("admin/utilisateurs/formulaire"))
				.andExpect(model().attributeHasFieldErrors("utilisateurForm", "nom", "email", "motDePasse"));
		assertThat(repository.findByEmailIgnoreCase("pas-un-email")).isEmpty();
	}

	@Test
	void creationSansMotDePasseOuRoleRefusee() throws Exception {
		mvc.perform(admin(post("/admin/utilisateurs")).param("nom", "Bob").param("email", "bob@test.fr")
				.param("role", "USER"))
				.andExpect(model().attributeHasFieldErrors("utilisateurForm", "motDePasse"));
		mvc.perform(admin(post("/admin/utilisateurs")).param("nom", "Bob").param("email", "bob@test.fr")
				.param("motDePasse", "motdepasse1").param("role", "SUPERADMIN"))
				.andExpect(model().attributeHasFieldErrors("utilisateurForm", "role"));
		assertThat(repository.findByEmailIgnoreCase("bob@test.fr")).isEmpty();
	}

	@Test
	void emailDejaUtiliseRefuse() throws Exception {
		mvc.perform(admin(post("/admin/utilisateurs")).param("nom", "Autre").param("email", "ADMIN@test.fr")
				.param("motDePasse", "motdepasse1").param("role", "USER"))
				.andExpect(model().attributeHasFieldErrors("utilisateurForm", "email"));
	}

	@Test
	void modificationSansMotDePasseGardeLeHash() throws Exception {
		Utilisateur u = nouveau("carl@test.fr", "Carl", Role.USER, "ancien-mdp-123");
		String hash = u.getMotDePasse();
		mvc.perform(admin(post("/admin/utilisateurs/" + u.getId())).param("nom", "Carl2")
				.param("email", "carl@test.fr").param("motDePasse", "").param("role", "ADMIN"))
				.andExpect(status().is3xxRedirection());
		Utilisateur modifie = repository.findById(u.getId()).orElseThrow();
		assertThat(modifie.getNom()).isEqualTo("Carl2");
		assertThat(modifie.getRole()).isEqualTo(Role.ADMIN);
		assertThat(modifie.getMotDePasse()).isEqualTo(hash);
	}

	@Test
	void modificationAvecMotDePasseLeChange() throws Exception {
		Utilisateur u = nouveau("dan@test.fr", "Dan", Role.USER, "ancien-mdp-123");
		mvc.perform(admin(post("/admin/utilisateurs/" + u.getId())).param("nom", "Dan")
				.param("email", "dan@test.fr").param("motDePasse", "nouveau-mdp-456").param("role", "USER"))
				.andExpect(status().is3xxRedirection());
		assertThat(encoder.matches("nouveau-mdp-456",
				repository.findById(u.getId()).orElseThrow().getMotDePasse())).isTrue();
	}

	@Test
	void adminNePeutPasChangerSonRoleNiSeSupprimer() throws Exception {
		mvc.perform(admin(post("/admin/utilisateurs/" + admin.getId())).param("nom", "Admin")
				.param("email", "admin@test.fr").param("role", "USER"))
				.andExpect(model().attributeHasFieldErrors("utilisateurForm", "role"));
		mvc.perform(admin(post("/admin/utilisateurs/" + admin.getId() + "/supprimer")))
				.andExpect(status().is3xxRedirection());
		assertThat(repository.findById(admin.getId())).isPresent();
		assertThat(repository.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
	}

	@Test
	void suppression() throws Exception {
		Utilisateur u = nouveau("eve@test.fr", "Eve", Role.USER, "motdepasse1");
		mvc.perform(admin(post("/admin/utilisateurs/" + u.getId() + "/supprimer")))
				.andExpect(status().is3xxRedirection());
		assertThat(repository.findById(u.getId())).isEmpty();
	}

	@Test
	void premierAdminCreeUneSeuleFois() {
		repository.deleteAll();
		assertThat(service.initialiserPremierAdmin("Premier@Test.fr", "Premier", "motdepasse-1")).isTrue();
		Utilisateur premier = repository.findByEmailIgnoreCase("premier@test.fr").orElseThrow();
		assertThat(premier.getRole()).isEqualTo(Role.ADMIN);
		String hash = premier.getMotDePasse();
		assertThat(hash).isNotEqualTo("motdepasse-1");

		// Un administrateur existe : aucun effet, aucune réinitialisation
		assertThat(service.initialiserPremierAdmin("premier@test.fr", "Premier", "autre-mdp-123")).isFalse();
		assertThat(repository.findByEmailIgnoreCase("premier@test.fr").orElseThrow().getMotDePasse()).isEqualTo(hash);
	}

	@Test
	void premierAdminPromeutUnCompteExistant() {
		repository.deleteAll();
		Utilisateur existant = new Utilisateur();
		existant.setEmail("ancien@test.fr");
		existant.setNom("Ancien");
		repository.save(existant);
		assertThat(service.initialiserPremierAdmin("ancien@test.fr", "Ignoré", "motdepasse-1")).isTrue();
		Utilisateur u = repository.findByEmailIgnoreCase("ancien@test.fr").orElseThrow();
		assertThat(u.getRole()).isEqualTo(Role.ADMIN);
		assertThat(u.getNom()).isEqualTo("Ancien");
		assertThat(repository.count()).isEqualTo(1);
	}
}
