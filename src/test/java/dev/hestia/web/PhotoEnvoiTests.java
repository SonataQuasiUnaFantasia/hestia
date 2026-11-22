package dev.hestia.web;

import static dev.hestia.web.ImagesDeTest.decoder;
import static dev.hestia.web.ImagesDeTest.jpeg;
import static dev.hestia.web.ImagesDeTest.png;
import static dev.hestia.web.ImagesDeTest.pngUni;
import static dev.hestia.web.ImagesDeTest.ressource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.hestia.web.entity.FormatImage;
import dev.hestia.web.entity.Photo;
import dev.hestia.web.repository.PhotoRepository;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/** Envoi d'une image (un appel du script d'envoi par lot) : réponse JSON, persistance, stockage, refus. */
class PhotoEnvoiTests extends PhotoTestBase {

	@MockitoSpyBean PhotoRepository photoRepositoryEspion;

	@AfterEach
	void retablir() {
		reset(photoRepositoryEspion);
	}

	/** Refus attendu : 400 avec un message JSON lisible, et rien d'écrit. */
	private ResultActions assertRefus(ResultActions resultat, String motif) throws Exception {
		resultat.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.erreur", containsString(motif)));
		assertAucuneTrace();
		return resultat;
	}

	// --- Publication ---

	@Test
	void envoiDUneImageCreeUnePhoto() throws Exception {
		byte[] image = jpeg(640, 480);
		LocalDateTime avant = LocalDateTime.now().minusSeconds(1);

		String reponse = mvc.perform(envoi(alice, fichier("vacances.jpg", image)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andReturn().getResponse().getContentAsString();

		Photo photo = seulePhoto();
		assertThat(reponse).isEqualTo("{\"id\":" + photo.getId() + "}");
		assertThat(photo.getAuteur().getId()).isEqualTo(alice.getId());
		assertThat(photo.getDateCreation()).isBetween(avant, LocalDateTime.now().plusSeconds(1));
		assertThat(photo.getFormatOriginal()).isEqualTo(FormatImage.JPEG);
		assertThat(photo.getLargeur()).isEqualTo(640);
		assertThat(photo.getHauteur()).isEqualTo(480);
		// Original identique aux octets envoyés, version optimisée décodable.
		assertThat(Files.readAllBytes(original(photo))).isEqualTo(image);
		assertThat(decoder(Files.readAllBytes(optimisee(photo))).getWidth()).isEqualTo(640);
		assertThat(fichiers("originals")).hasSize(1);
		assertThat(fichiers("optimized")).hasSize(1);
	}

	@Test
	void lotEnvoyeImageParImageChaqueImageDevientUnePhoto() throws Exception {
		mvc.perform(envoi(alice, fichier("a.jpg", jpeg(300, 200)))).andExpect(status().isCreated());
		mvc.perform(envoi(alice, fichier("b.png", png(200, 300)))).andExpect(status().isCreated());
		mvc.perform(envoi(alice, fichier("c.webp", ressource("paysage.webp")))).andExpect(status().isCreated());

		assertThat(photoRepository.findAll()).extracting(Photo::getFormatOriginal)
				.containsExactlyInAnyOrder(FormatImage.JPEG, FormatImage.PNG, FormatImage.WEBP);
		assertThat(fichiers("originals")).hasSize(3);
		assertThat(fichiers("optimized")).hasSize(3);
	}

	@Test
	void imageRefuseeDansUnLotNAffectePasLesAutres() throws Exception {
		mvc.perform(envoi(alice, fichier("ok-1.jpg", jpeg(300, 200)))).andExpect(status().isCreated());
		mvc.perform(envoi(alice, fichier("ko.jpg", "pas une image".getBytes(StandardCharsets.UTF_8))))
				.andExpect(status().isBadRequest());
		mvc.perform(envoi(alice, fichier("ok-2.jpg", jpeg(200, 300)))).andExpect(status().isCreated());

		assertThat(photoRepository.count()).isEqualTo(2);
		assertThat(fichiers("originals")).hasSize(2);
		assertThat(fichiers("optimized")).hasSize(2);
	}

	// --- Authentification et auteur ---

	@Test
	void utilisateurNonAuthentifieNePeutPasEnvoyer() throws Exception {
		mvc.perform(multipart("/photos").file(fichier("a.jpg", jpeg(100, 100))).with(csrf().asHeader()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
		assertAucuneTrace();
	}

	@Test
	void envoiSansJetonCsrfRefuse() throws Exception {
		mvc.perform(multipart("/photos").file(fichier("a.jpg", jpeg(100, 100))).with(connecte(alice)))
				.andExpect(status().isForbidden());
		mvc.perform(multipart("/photos").file(fichier("a.jpg", jpeg(100, 100))).with(connecte(alice))
						.with(csrf().asHeader().useInvalidToken()))
				.andExpect(status().isForbidden());
		assertAucuneTrace();
	}

	@Test
	void auteurToujoursIssuDeLaSessionJamaisDeLaRequete() throws Exception {
		String idBob = bob.getId().toString();
		mvc.perform(envoi(alice, fichier("a.jpg", jpeg(100, 100)))
						.param("auteur", idBob).param("auteurId", idBob).param("auteur.id", idBob)
						.param("idAuteur", idBob).param("auteur_id", idBob).param("id", idBob))
				.andExpect(status().isCreated());
		assertThat(seulePhoto().getAuteur().getId()).isEqualTo(alice.getId());
	}

	// --- Validation du fichier ---

	@Test
	void requeteSansImageRefusee() throws Exception {
		assertRefus(mvc.perform(envoi(alice, null)), "aucune image");
		// Champ de fichier vide : le navigateur envoie une partie sans nom ni contenu.
		assertRefus(mvc.perform(envoi(alice,
				new MockMultipartFile("image", "", "application/octet-stream", new byte[0]))), "aucune image");
		// Fichier envoyé sous un autre nom de champ : ignoré.
		assertRefus(mvc.perform(envoi(alice, null)
				.file(new MockMultipartFile("images", "a.jpg", "image/jpeg", jpeg(100, 100)))), "aucune image");
	}

	@Test
	void formatDetecteDansLeContenuEtNonDansLeNom() throws Exception {
		// Un PNG nommé .jpg et déclaré image/jpeg reste accepté : il est enregistré comme PNG.
		byte[] image = png(120, 80);
		mvc.perform(envoi(alice, fichier("photo.jpg", image))).andExpect(status().isCreated());
		Photo photo = seulePhoto();
		assertThat(photo.getFormatOriginal()).isEqualTo(FormatImage.PNG);
		assertThat(photo.getReferenceOriginale()).endsWith(".png");
		assertThat(Files.readAllBytes(original(photo))).isEqualTo(image);
	}

	@Test
	void fichierInvalideRefuseAvecUnMessageLisible() throws Exception {
		// Le détail de chaque refus est testé dans TraitementImageTests ; ici, la réponse renvoyée au script.
		byte[] image = jpeg(400, 300);
		assertRefus(mvc.perform(envoi(alice, fichier("vide.jpg", new byte[0]))), "aucune image");
		assertRefus(mvc.perform(envoi(alice, fichier("photo.jpg",
				"<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)))), "format non pris en charge");
		assertRefus(mvc.perform(envoi(alice, fichier("tronque.jpg", Arrays.copyOf(image, image.length / 2)))),
				"illisible ou corrompu");
	}

	@Test
	void fichierTropVolumineuxRefuse() throws Exception {
		byte[] enorme = Arrays.copyOf(jpeg(100, 100), (int) tailleMax.toBytes() + 1);
		assertRefus(mvc.perform(envoi(alice, fichier("enorme.jpg", enorme))), "taille maximale");
	}

	@Test
	void dimensionsExcessivesRefuseesSelonLaConfiguration() throws Exception {
		assertRefus(mvc.perform(envoi(alice, fichier("large.png", pngUni(dimensionMax + 1, 10)))),
				"dimensions");
	}

	// --- Traitement de bout en bout ---

	@Test
	void grandeImageStockeeIntacteEtServieReduite() throws Exception {
		byte[] image = jpeg(2400, 1600);
		mvc.perform(envoi(alice, fichier("grande.jpg", image))).andExpect(status().isCreated());

		Photo photo = seulePhoto();
		assertThat(photo.getLargeur()).isEqualTo(1080);
		assertThat(photo.getHauteur()).isEqualTo(720);
		// L'original n'est ni redimensionné ni réencodé.
		assertThat(Files.readAllBytes(original(photo))).isEqualTo(image);
		BufferedImage optimisee = decoder(Files.readAllBytes(optimisee(photo)));
		assertThat(optimisee.getWidth()).isEqualTo(1080);
		assertThat(optimisee.getHeight()).isEqualTo(720);
	}

	@Test
	void orientationExifAppliqueeALaVersionOptimiseeSeulement() throws Exception {
		byte[] image = ressource("exif-6-grand.jpg"); // pixels 1600x1200, visuel portrait
		mvc.perform(envoi(alice, fichier("telephone.jpg", image))).andExpect(status().isCreated());

		Photo photo = seulePhoto();
		assertThat(photo.getLargeur()).isEqualTo(810);
		assertThat(photo.getHauteur()).isEqualTo(1080);
		BufferedImage optimisee = decoder(Files.readAllBytes(optimisee(photo)));
		assertThat(optimisee.getWidth()).isEqualTo(810);
		assertThat(optimisee.getHeight()).isEqualTo(1080);
		ImagesDeTest.assertQuadrantsStandard(optimisee);
		// L'original garde ses octets, donc ses pixels non pivotés et sa balise EXIF.
		byte[] stocke = Files.readAllBytes(original(photo));
		assertThat(stocke).isEqualTo(image);
		assertThat(decoder(stocke).getWidth()).isEqualTo(1600);
	}

	@Test
	void referencesEnBaseRelativesEtGenereesParLeServeur() throws Exception {
		mvc.perform(envoi(alice, fichier("../../etc/passwd.jpg", jpeg(100, 100)))).andExpect(status().isCreated());
		mvc.perform(envoi(alice, fichier("C:\\Users\\alice\\photo.jpg", jpeg(100, 100))))
				.andExpect(status().isCreated());

		String motif = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.jpg";
		for (Photo photo : photoRepository.findAll()) {
			assertThat(photo.getReferenceOriginale()).matches(motif.replace("jpg", "(jpg|png|webp)"));
			assertThat(photo.getReferenceOptimisee()).matches(motif);
		}
		assertThat(fichiers("optimized")).hasSize(2)
				.allSatisfy(f -> assertThat(f.getFileName().toString()).matches(motif));
	}

	// --- Échecs internes ---

	@Test
	void echecDEnregistrementEnBaseEffaceLesFichiersEcrits() throws Exception {
		doThrow(new DataIntegrityViolationException("panne simulée de la base"))
				.when(photoRepositoryEspion).save(any(Photo.class));

		assertThatThrownBy(() -> photoService.publier(alice.getId(), fichier("a.jpg", jpeg(300, 200))))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertAucuneTrace();
	}

	@Test
	void messageDErreurRenvoyeEnJsonEtNonInterpreteCommeDuHtml() throws Exception {
		mvc.perform(envoi(alice, fichier("x.jpg", new byte[] {1, 2, 3})))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/json"));
	}
}
