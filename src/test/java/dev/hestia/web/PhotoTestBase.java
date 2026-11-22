package dev.hestia.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import dev.hestia.web.entity.Photo;
import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.repository.PhotoRepository;
import dev.hestia.web.repository.UtilisateurRepository;
import dev.hestia.web.security.UtilisateurDetailsService;
import dev.hestia.web.service.PhotoService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * Socle des tests d'intégration des photos : vraie base (H2), vrai système de fichiers.
 * Pas de transaction de test : le service valide ses propres transactions, comme en production.
 * Chaque test nettoie donc la base et le répertoire de stockage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class PhotoTestBase {

	@Autowired MockMvc mvc;
	@Autowired UtilisateurRepository utilisateurRepository;
	@Autowired PhotoRepository photoRepository;
	@Autowired PhotoService photoService;
	@Autowired UtilisateurDetailsService detailsService;
	@Autowired PasswordEncoder encoder;
	@Value("${app.upload.directory}") Path repertoireStockage;
	@Value("${app.upload.max-file-size}") DataSize tailleMax;
	@Value("${app.upload.max-dimension}") int dimensionMax;
	@Autowired TransactionTemplate transactions;

	Utilisateur alice;
	Utilisateur bob;
	Utilisateur admin;

	private final List<Utilisateur> comptes = new ArrayList<>();

	@BeforeEach
	void preparer() {
		viderStockage();
		alice = compte("alice@photos.test", "Alice", Role.USER);
		bob = compte("bob@photos.test", "Bob", Role.USER);
		admin = compte("admin@photos.test", "Administratrice", Role.ADMIN);
	}

	@AfterEach
	void nettoyer() {
		photoRepository.deleteAll();
		utilisateurRepository.deleteAll(comptes);
		comptes.clear();
		viderStockage();
	}

	Utilisateur compte(String email, String nom, Role role) {
		Utilisateur u = new Utilisateur();
		u.setEmail(email);
		u.setNom(nom);
		u.setRole(role);
		u.setMotDePasse(encoder.encode("motdepasse-test"));
		comptes.add(utilisateurRepository.save(u));
		return u;
	}

	/** Principal réel de l'application, chargé comme lors d'une connexion. */
	RequestPostProcessor connecte(Utilisateur utilisateur) {
		return user(detailsService.loadUserByUsername(utilisateur.getEmail()));
	}

	static MockMultipartFile fichier(String nom, byte[] octets) {
		return new MockMultipartFile("image", nom, "image/jpeg", octets);
	}

	/** Envoi d'une image tel que le fait le script de la page d'envoi : authentifié, avec jeton CSRF. */
	MockMultipartHttpServletRequestBuilder envoi(Utilisateur utilisateur, MockMultipartFile fichier) {
		MockMultipartHttpServletRequestBuilder requete = multipart("/photos");
		if (fichier != null) {
			requete.file(fichier);
		}
		requete.with(connecte(utilisateur)).with(csrf().asHeader());
		return requete;
	}

	/** Publie une photo par le service et la renvoie rechargée depuis la base, auteur chargé. */
	Photo publier(Utilisateur auteur, byte[] image) {
		return recharger(photoService.publier(auteur.getId(), fichier("photo.jpg", image)).getId());
	}

	Photo recharger(Long id) {
		return transactions.execute(statut -> {
			Photo photo = photoRepository.findById(id).orElseThrow();
			photo.getAuteur().getNom();
			return photo;
		});
	}

	Photo seulePhoto() {
		List<Photo> toutes = photoRepository.findAll();
		assertThat(toutes).hasSize(1);
		return recharger(toutes.get(0).getId());
	}

	Path repertoire(String nom) {
		return repertoireStockage.toAbsolutePath().resolve(nom);
	}

	Path original(Photo photo) {
		return repertoire("originals").resolve(photo.getReferenceOriginale());
	}

	Path optimisee(Photo photo) {
		return repertoire("optimized").resolve(photo.getReferenceOptimisee());
	}

	List<Path> fichiers(String nomRepertoire) {
		try (Stream<Path> contenu = Files.list(repertoire(nomRepertoire))) {
			return contenu.sorted().toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Aucune trace : ni photo en base, ni fichier. */
	void assertAucuneTrace() {
		assertThat(photoRepository.count()).isZero();
		assertStockageVide();
	}

	void assertStockageVide() {
		assertThat(fichiers("originals")).isEmpty();
		assertThat(fichiers("optimized")).isEmpty();
	}

	private void viderStockage() {
		for (String nom : List.of("originals", "optimized")) {
			for (Path fichier : fichiers(nom)) {
				try {
					Files.delete(fichier);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
	}
}
