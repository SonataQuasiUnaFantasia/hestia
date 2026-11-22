package dev.hestia.web;

import static dev.hestia.web.ImagesDeTest.decoder;
import static dev.hestia.web.ImagesDeTest.jpeg;
import static dev.hestia.web.ImagesDeTest.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.hestia.web.dto.PhotoVue;
import dev.hestia.web.entity.Photo;
import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Slice;

/** Galerie des photos, page d'envoi par lot et accès aux images. */
class PhotoAffichageTests extends PhotoTestBase {

	private static final byte[] PORTRAIT = jpeg(300, 500);

	/** Identifiants des photos dans l'ordre où le HTML les affiche. */
	private static List<Long> photosAffichees(String html) {
		Matcher image = Pattern.compile("src=\"/photos/(\\d+)/image\"").matcher(html);
		List<Long> ids = new ArrayList<>();
		while (image.find()) {
			ids.add(Long.valueOf(image.group(1)));
		}
		return ids;
	}

	private String galerie(Utilisateur utilisateur) throws Exception {
		return mvc.perform(get("/photos").with(connecte(utilisateur)))
				.andExpect(status().isOk())
				.andExpect(view().name("photos/liste"))
				.andReturn().getResponse().getContentAsString();
	}

	// --- Galerie ---

	@Test
	void galerieDeLaPlusRecenteALaPlusAncienne() throws Exception {
		Photo premiere = publier(alice, PORTRAIT);
		Photo deuxieme = publier(bob, png(400, 400));
		Photo troisieme = publier(alice, jpeg(500, 300));

		Slice<PhotoVue> vues = photoService.lister(bob.getId(), 0);
		assertThat(vues.getContent()).extracting(PhotoVue::id)
				.containsExactly(troisieme.getId(), deuxieme.getId(), premiere.getId());
		assertThat(photosAffichees(galerie(bob)))
				.containsExactly(troisieme.getId(), deuxieme.getId(), premiere.getId());
	}

	@Test
	void galerieAfficheAuteurDateEtImagesOptimisees() throws Exception {
		Utilisateur pirate = compte("pirate@photos.test", "<b>Pirate</b>", Role.USER);
		Photo photo = publier(pirate, jpeg(2400, 1600));

		mvc.perform(get("/photos").with(connecte(bob)))
				.andExpect(status().isOk())
				// Le nom est échappé : aucun HTML saisi n'est interprété.
				.andExpect(content().string(containsString("&lt;b&gt;Pirate&lt;/b&gt;")))
				.andExpect(content().string(containsString("src=\"/photos/" + photo.getId() + "/image\"")))
				.andExpect(content().string(containsString("width=\"1080\"")))
				.andExpect(content().string(containsString("height=\"720\"")))
				.andExpect(content().string(containsString(String.valueOf(photo.getDateCreation().getYear()))))
				// Ni l'email de l'auteur ni un nom de fichier du stockage n'apparaissent.
				.andExpect(content().string(not(containsString(pirate.getEmail()))))
				.andExpect(content().string(not(containsString(photo.getReferenceOriginale()))))
				.andExpect(content().string(not(containsString(photo.getReferenceOptimisee()))));
	}

	@Test
	void galerieVide() throws Exception {
		assertThat(galerie(alice)).contains("Aucune photo");
		publier(alice, PORTRAIT);
		assertThat(galerie(alice)).doesNotContain("Aucune photo");
	}

	@Test
	void galeriePaginee() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			ids.add(publier(alice, jpeg(20, 10)).getId());
		}
		String premierePage = galerie(alice);
		assertThat(photosAffichees(premierePage)).hasSize(24).doesNotContain(ids.get(0));
		assertThat(premierePage).contains("/photos?page=1");
		String seconde = mvc.perform(get("/photos").param("page", "1").with(connecte(alice)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(photosAffichees(seconde)).containsExactly(ids.get(0));
		assertThat(seconde).contains("/photos?page=0");
		mvc.perform(get("/photos").param("page", "-5").with(connecte(alice))).andExpect(status().isOk());
	}

	// --- Page d'envoi ---

	@Test
	void pageDEnvoiParLot() throws Exception {
		mvc.perform(get("/photos/envoi").with(connecte(alice)))
				.andExpect(status().isOk())
				.andExpect(view().name("photos/envoi"))
				.andExpect(content().string(containsString("type=\"file\"")))
				.andExpect(content().string(containsString("multiple")))
				.andExpect(content().string(containsString("data-url=\"/photos\"")))
				// Limite affichée et appliquée par le script : celle de la configuration.
				.andExpect(content().string(containsString(
						"data-taille-max=\"" + tailleMax.toBytes() + "\"")))
				// Jeton CSRF envoyé par le script dans un en-tête.
				.andExpect(content().string(containsString("<meta name=\"_csrf\" content=\"")))
				.andExpect(content().string(containsString("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"")))
				// Décompte et barre de progression.
				.andExpect(content().string(containsString("id=\"decompte\"")))
				.andExpect(content().string(containsString("<progress id=\"progression\"")))
				.andExpect(content().string(containsString("xhr.upload.onprogress")))
				// Aucune saisie de texte.
				.andExpect(content().string(not(containsString("<textarea"))))
				.andExpect(content().string(not(containsString("name=\"texte\""))));
	}

	@Test
	void lienVersLesPhotosDepuisLAccueil() throws Exception {
		mvc.perform(get("/").with(connecte(alice)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("href=\"/photos\"")));
	}

	@Test
	void pagesReserveesAuxUtilisateursAuthentifies() throws Exception {
		Photo photo = publier(alice, PORTRAIT);
		for (String chemin : List.of("/photos", "/photos/envoi", "/photos/" + photo.getId() + "/image")) {
			mvc.perform(get(chemin)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
		}
	}

	// --- Accès aux images ---

	@Test
	void imageServieEnVersionOptimiseeAvecUnTypeCoherent() throws Exception {
		byte[] originalEnvoye = png(2000, 1000);
		Photo photo = publier(alice, originalEnvoye);

		byte[] servie = mvc.perform(get("/photos/" + photo.getId() + "/image").with(connecte(bob)))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "image/jpeg"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andReturn().getResponse().getContentAsByteArray();

		// C'est exactement le fichier optimisé, jamais l'original.
		assertThat(servie).isEqualTo(Files.readAllBytes(optimisee(photo))).isNotEqualTo(originalEnvoye);
		BufferedImage decodee = decoder(servie);
		assertThat(decodee.getWidth()).isEqualTo(1080);
		assertThat(decodee.getHeight()).isEqualTo(540);
	}

	@Test
	void aucunCheminNiNomDeFichierAccepteDansLUrl() throws Exception {
		Photo photo = publier(alice, PORTRAIT);
		for (String chemin : List.of(
				"/photos/999999999/image",
				"/photos/" + photo.getReferenceOptimisee() + "/image",
				"/photos/" + photo.getReferenceOriginale() + "/image",
				"/photos/..%2F..%2Fapplication.properties/image",
				"/photos/%2Fetc%2Fpasswd/image",
				"/photos/" + photo.getId() + "/image/../../../etc/passwd",
				"/photos/originals/" + photo.getReferenceOriginale(),
				"/originals/" + photo.getReferenceOriginale(),
				"/optimized/" + photo.getReferenceOptimisee(),
				"/uploads/optimized/" + photo.getReferenceOptimisee())) {
			int statut = mvc.perform(get(chemin).with(connecte(alice))).andReturn().getResponse().getStatus();
			assertThat(statut).as(chemin).isBetween(400, 405);
		}
	}

	@Test
	void imageDontLeFichierADisparuIntrouvable() throws Exception {
		Photo photo = publier(alice, PORTRAIT);
		Files.delete(optimisee(photo));
		mvc.perform(get("/photos/" + photo.getId() + "/image").with(connecte(alice)))
				.andExpect(status().isNotFound());
	}
}
