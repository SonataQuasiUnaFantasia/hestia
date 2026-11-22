package dev.hestia.web;

import static dev.hestia.web.ImagesDeTest.jpeg;
import static dev.hestia.web.ImagesDeTest.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.hestia.web.entity.Photo;
import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.security.UtilisateurPrincipal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Suppression : autorisations vérifiées côté serveur, effacement des fichiers. */
class PhotoSuppressionTests extends PhotoTestBase {

	private MockHttpServletRequestBuilder suppression(Photo photo) {
		return post("/photos/" + photo.getId() + "/supprimer").with(csrf());
	}

	/** Contenu de chaque fichier du stockage, pour prouver qu'un refus n'a rien modifié. */
	private Map<Path, byte[]> etatDuStockage() throws IOException {
		Map<Path, byte[]> etat = new HashMap<>();
		for (String nom : List.of("originals", "optimized")) {
			for (Path fichier : fichiers(nom)) {
				etat.put(fichier, Files.readAllBytes(fichier));
			}
		}
		return etat;
	}

	private void assertIntacte(Photo photo, Map<Path, byte[]> stockageAvant) throws IOException {
		Photo relue = recharger(photo.getId());
		assertThat(relue.getAuteur().getId()).isEqualTo(photo.getAuteur().getId());
		assertThat(relue.getReferenceOptimisee()).isEqualTo(photo.getReferenceOptimisee());
		Map<Path, byte[]> apres = etatDuStockage();
		assertThat(apres.keySet()).isEqualTo(stockageAvant.keySet());
		stockageAvant.forEach((fichier, octets) -> assertThat(apres.get(fichier)).isEqualTo(octets));
	}

	private void assertSupprimee(Photo photo) {
		assertThat(photoRepository.findById(photo.getId())).isEmpty();
		assertThat(original(photo)).doesNotExist();
		assertThat(optimisee(photo)).doesNotExist();
	}

	// --- Règles d'autorisation ---

	@Test
	void auteurSupprimeSaProprePhoto() throws Exception {
		Photo photo = publier(alice, jpeg(300, 200));

		mvc.perform(suppression(photo).with(connecte(alice)))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/photos"));

		assertSupprimee(photo);
		assertStockageVide();
	}

	@Test
	void auteurNePeutPasSupprimerLaPhotoDUnAutre() throws Exception {
		Photo deBob = publier(bob, jpeg(300, 200));
		publier(alice, jpeg(200, 300)); // Alice est auteur, mais d'une autre photo
		Map<Path, byte[]> avant = etatDuStockage();

		mvc.perform(suppression(deBob).with(connecte(alice))).andExpect(status().isForbidden());

		assertIntacte(deBob, avant);
		assertThat(photoRepository.count()).isEqualTo(2);
	}

	@Test
	void administrateurSupprimeNImporteQuellePhoto() throws Exception {
		Photo deAlice = publier(alice, jpeg(300, 200));
		Photo deBob = publier(bob, jpeg(200, 300));

		mvc.perform(suppression(deAlice).with(connecte(admin))).andExpect(status().is3xxRedirection());
		mvc.perform(suppression(deBob).with(connecte(admin))).andExpect(status().is3xxRedirection());

		assertSupprimee(deAlice);
		assertSupprimee(deBob);
		assertStockageVide();
	}

	@Test
	void utilisateurNonAuthentifieNePeutPasSupprimer() throws Exception {
		Photo photo = publier(alice, jpeg(300, 200));
		Map<Path, byte[]> avant = etatDuStockage();

		mvc.perform(suppression(photo))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));

		assertIntacte(photo, avant);
	}

	@Test
	void requeteDirecteAvecIdentifiantsManipulesNeContournePasLesControles() throws Exception {
		Photo deAlice = publier(alice, jpeg(300, 200));
		Photo deBob = publier(bob, jpeg(200, 300));
		Map<Path, byte[]> avant = etatDuStockage();
		String idAlice = alice.getId().toString();

		// Bob se déclare auteur de la photo d'Alice par tous les paramètres imaginables.
		mvc.perform(suppression(deAlice).with(connecte(bob))
						.param("auteur", idAlice).param("auteurId", idAlice).param("auteur.id", idAlice)
						.param("idConnecte", idAlice).param("idAuteur", idAlice).param("role", "ADMIN")
						.param("id", deBob.getId().toString()))
				.andExpect(status().isForbidden());
		// L'identifiant de sa propre photo en paramètre ne change pas la cible de l'URL.
		mvc.perform(post("/photos/" + deAlice.getId() + "/supprimer?id=" + deBob.getId())
						.with(csrf()).with(connecte(bob)))
				.andExpect(status().isForbidden());

		assertIntacte(deAlice, avant);
		assertIntacte(deBob, avant);
		// Identifiant inexistant ou non numérique : rien n'est supprimé.
		mvc.perform(post("/photos/999999999/supprimer").with(csrf()).with(connecte(bob)))
				.andExpect(status().isNotFound());
		mvc.perform(post("/photos/abc/supprimer").with(csrf()).with(connecte(bob)))
				.andExpect(status().isBadRequest());
		assertThat(photoRepository.count()).isEqualTo(2);
	}

	@Test
	void droitsIssusDuRoleEnBaseEtNonDeLaSession() throws Exception {
		Photo photo = publier(alice, jpeg(300, 200));
		Map<Path, byte[]> avant = etatDuStockage();

		// Session portant l'autorité ROLE_ADMIN pour un compte qui n'est que USER en base.
		RequestPostProcessor sessionAdminForgee = user(new UtilisateurPrincipal(bob.getId(), bob.getEmail(),
				"x", true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		mvc.perform(suppression(photo).with(sessionAdminForgee)).andExpect(status().isForbidden());
		// Session d'un compte supprimé depuis.
		RequestPostProcessor compteDisparu = user(new UtilisateurPrincipal(999_999_999L, "fantome@test.fr",
				"x", true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		mvc.perform(suppression(photo).with(compteDisparu)).andExpect(status().isForbidden());
		assertIntacte(photo, avant);

		mvc.perform(suppression(photo).with(connecte(admin))).andExpect(status().is3xxRedirection());
		assertSupprimee(photo);
	}

	@Test
	void controleEffectueAussiParLeServiceHorsDeTouteRequeteHttp() throws Exception {
		Photo photo = publier(alice, jpeg(300, 200));
		Map<Path, byte[]> avant = etatDuStockage();

		assertThatThrownBy(() -> photoService.supprimer(photo.getId(), bob.getId()))
				.isInstanceOf(AccessDeniedException.class);

		assertIntacte(photo, avant);
	}

	@Test
	void compteAyantDesPhotosNonSupprimableParLAdministration() throws Exception {
		// Fonction existante de gestion des utilisateurs : la clé étrangère protège les photos.
		Photo photo = publier(alice, jpeg(300, 200));
		Map<Path, byte[]> avant = etatDuStockage();

		mvc.perform(post("/admin/utilisateurs/" + alice.getId() + "/supprimer").with(csrf()).with(connecte(admin)))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/utilisateurs"))
				.andExpect(flash().attributeExists("erreur"));

		assertThat(utilisateurRepository.findById(alice.getId())).isPresent();
		assertIntacte(photo, avant);
	}

	// --- Méthode HTTP et CSRF ---

	@Test
	void jamaisDeSuppressionParGetNiSansJetonCsrf() throws Exception {
		Photo photo = publier(alice, jpeg(300, 200));
		Map<Path, byte[]> avant = etatDuStockage();
		String url = "/photos/" + photo.getId() + "/supprimer";

		mvc.perform(get(url).with(connecte(alice))).andExpect(status().isMethodNotAllowed());
		mvc.perform(get(url).with(connecte(admin))).andExpect(status().isMethodNotAllowed());
		mvc.perform(post(url).with(connecte(alice))).andExpect(status().isForbidden()); // sans CSRF
		mvc.perform(delete("/photos/" + photo.getId()).with(connecte(alice)).with(csrf()))
				.andExpect(status().is4xxClientError());

		assertIntacte(photo, avant);
	}

	// --- Interface ---

	@Test
	void boutonDeSuppressionAfficheSeulementAuxUtilisateursAutorises() throws Exception {
		Photo deAlice = publier(alice, jpeg(300, 200));
		Photo deBob = publier(bob, jpeg(200, 300));
		String actionAlice = "action=\"/photos/" + deAlice.getId() + "/supprimer\"";
		String actionBob = "action=\"/photos/" + deBob.getId() + "/supprimer\"";

		String vueParAlice = mvc.perform(get("/photos").with(connecte(alice)))
				.andReturn().getResponse().getContentAsString();
		assertThat(vueParAlice).contains(actionAlice).doesNotContain(actionBob);

		String vueParBob = mvc.perform(get("/photos").with(connecte(bob)))
				.andReturn().getResponse().getContentAsString();
		assertThat(vueParBob).contains(actionBob).doesNotContain(actionAlice);

		String vueParAdmin = mvc.perform(get("/photos").with(connecte(admin)))
				.andReturn().getResponse().getContentAsString();
		assertThat(vueParAdmin).contains(actionAlice).contains(actionBob);
		// Le formulaire est un POST protégé par CSRF.
		assertThat(vueParAdmin).containsPattern("(?s)<form[^>]*" + actionAlice + "[^>]*method=\"post\"[^>]*>"
				+ "\\s*<input type=\"hidden\" name=\"_csrf\"");

		Utilisateur carl = compte("carl@photos.test", "Carl", Role.USER);
		String vueParCarl = mvc.perform(get("/photos").with(connecte(carl)))
				.andReturn().getResponse().getContentAsString();
		assertThat(vueParCarl).contains("/photos/" + deAlice.getId() + "/image").doesNotContain("/supprimer");
	}

	// --- Fichiers ---

	@Test
	void fichiersDesAutresPhotosPreserves() throws Exception {
		Photo aSupprimer = publier(alice, jpeg(300, 200));
		Photo aGarder = publier(alice, png(210, 310));
		Path etranger = Files.write(repertoire("originals").resolve("sans-rapport.txt"), new byte[] {1, 2, 3});
		try {
			mvc.perform(suppression(aSupprimer).with(connecte(alice))).andExpect(status().is3xxRedirection());

			assertSupprimee(aSupprimer);
			assertThat(original(aGarder)).exists();
			assertThat(optimisee(aGarder)).exists();
			assertThat(fichiers("originals")).hasSize(2); // l'original gardé + le fichier étranger
			assertThat(fichiers("optimized")).hasSize(1);
			assertThat(etranger).exists();
		} finally {
			Files.deleteIfExists(etranger);
		}
	}

	@Test
	void suppressionReussieMemeSiLesFichiersSontDejaAbsents() throws Exception {
		Photo photo = publier(alice, jpeg(300, 200));
		Files.delete(original(photo));
		Files.delete(optimisee(photo));

		mvc.perform(suppression(photo).with(connecte(alice))).andExpect(status().is3xxRedirection());

		assertSupprimee(photo);
		assertStockageVide();
	}

	@Test
	void fichierNonEffacableNEmpechePasLaSuppressionDeLaPhoto() throws Exception {
		Photo photo = publier(alice, jpeg(300, 200));
		Photo autre = publier(bob, jpeg(310, 210));
		Path optimises = repertoire("optimized");
		assumeTrue(Files.getFileStore(optimises).supportsFileAttributeView("posix"));
		Files.setPosixFilePermissions(optimises, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			assumeTrue(!Files.isWritable(optimises), "sans objet pour un super-utilisateur");

			// La suppression aboutit : la photo n'existe plus en base, seule sa version optimisée
			// reste sur disque (orpheline, jamais servie ni référencée).
			mvc.perform(suppression(photo).with(connecte(alice))).andExpect(status().is3xxRedirection());
			assertThat(photoRepository.findById(photo.getId())).isEmpty();
			assertThat(photoRepository.count()).isEqualTo(1);
			assertThat(original(photo)).doesNotExist();
			assertThat(optimisee(photo)).exists();
		} finally {
			Files.setPosixFilePermissions(optimises, PosixFilePermissions.fromString("rwxr-xr-x"));
		}

		assertThat(original(autre)).exists();
		assertThat(optimisee(autre)).exists();
	}
}
