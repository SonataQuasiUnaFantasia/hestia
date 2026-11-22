package dev.hestia.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hestia.web.dto.FichiersImage;
import dev.hestia.web.entity.FormatImage;
import dev.hestia.web.service.StockageImages;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Stockage sur un vrai système de fichiers (répertoire temporaire propre à chaque test). */
class StockageImagesTests {

	private static final String UUID_VALIDE = "123e4567-e89b-42d3-a456-426614174000";

	@TempDir
	Path temp;

	Path racine;
	StockageImages stockage;

	@BeforeEach
	void initialiser() {
		racine = temp.resolve("uploads");
		stockage = new StockageImages(racine);
	}

	private static List<Path> contenu(Path repertoire) throws IOException {
		try (Stream<Path> fichiers = Files.list(repertoire)) {
			return fichiers.sorted().toList();
		}
	}

	private static byte[] octets(String texte) {
		return texte.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void creeLesRepertoiresSousLeRepertoireConfigure() {
		assertThat(racine.resolve("originals")).isDirectory();
		assertThat(racine.resolve("optimized")).isDirectory();
	}

	@Test
	void ecrirePlaceLOriginalIntactEtLaVersionOptimisee() throws IOException {
		byte[] original = octets("original");
		byte[] optimisee = octets("optimisee");

		FichiersImage fichiers = stockage.ecrire(original, FormatImage.PNG, optimisee);

		assertThat(Files.readAllBytes(racine.resolve("originals").resolve(fichiers.referenceOriginale())))
				.isEqualTo(original);
		assertThat(Files.readAllBytes(racine.resolve("optimized").resolve(fichiers.referenceOptimisee())))
				.isEqualTo(optimisee);
		assertThat(stockage.lireOptimisee(fichiers.referenceOptimisee())).contains(optimisee);
	}

	@Test
	void referencesGenereesParLeServeurRelativesEtUniques() {
		FichiersImage a = stockage.ecrire(octets("a"), FormatImage.WEBP, octets("a2"));
		FichiersImage b = stockage.ecrire(octets("b"), FormatImage.WEBP, octets("b2"));
		String motif = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.";

		assertThat(a.referenceOriginale()).matches(motif + "webp").isNotEqualTo(b.referenceOriginale());
		assertThat(a.referenceOptimisee()).matches(motif + "jpg").isNotEqualTo(b.referenceOptimisee());
		// Un original et sa version optimisée partagent l'identifiant, avec leur propre extension.
		assertThat(a.referenceOriginale().replace(".webp", "")).isEqualTo(a.referenceOptimisee().replace(".jpg", ""));
		assertThat(stockage.ecrire(octets("c"), FormatImage.JPEG, octets("c2")).referenceOriginale()).matches(motif + "jpg");
		assertThat(stockage.ecrire(octets("d"), FormatImage.PNG, octets("d2")).referenceOriginale()).matches(motif + "png");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"../secret.jpg",
			"../../etc/passwd",
			"/etc/passwd",
			"originals/" + UUID_VALIDE + ".jpg",
			"..\\secret.jpg",
			UUID_VALIDE + ".jpg/../../x.jpg",
			UUID_VALIDE + ".exe",
			UUID_VALIDE + ".jpg\u0000.png",
			UUID_VALIDE,
			"photo.jpg",
			" " + UUID_VALIDE + ".jpg",
			"",
	})
	void referencesDangereusesRefuseesEnLectureEtSansEffetEnSuppression(String reference) throws IOException {
		Path exterieur = Files.write(temp.resolve("secret.jpg"), octets("secret"));

		assertThatThrownBy(() -> stockage.lireOptimisee(reference)).isInstanceOf(IllegalArgumentException.class);
		stockage.supprimer(new FichiersImage(reference, reference)); // ne lève rien et n'efface rien
		assertThat(exterieur).hasContent("secret");
	}

	@Test
	void referenceNulleRefusee() {
		assertThatThrownBy(() -> stockage.lireOptimisee(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void lectureDUnFichierAbsentRenvoieVide() {
		assertThat(stockage.lireOptimisee(UUID_VALIDE + ".jpg")).isEmpty();
	}

	@Test
	void lienSymboliqueJamaisSuiviEnLecture() throws IOException {
		Path secret = Files.write(temp.resolve("secret.txt"), octets("secret"));
		Files.createSymbolicLink(racine.resolve("optimized").resolve(UUID_VALIDE + ".jpg"), secret);

		assertThat(stockage.lireOptimisee(UUID_VALIDE + ".jpg")).isEmpty();
	}

	@Test
	void suppressionDUnLienSymboliqueNEffacePasSaCible() throws IOException {
		Path secret = Files.write(temp.resolve("secret.txt"), octets("secret"));
		Path lien = racine.resolve("originals").resolve(UUID_VALIDE + ".png");
		Files.createSymbolicLink(lien, secret);

		stockage.supprimer(new FichiersImage(UUID_VALIDE + ".png", UUID_VALIDE + ".jpg"));

		assertThat(lien).doesNotExist();
		assertThat(secret).hasContent("secret");
	}

	@Test
	void sousRepertoireRemplaceParUnLienSymboliqueRefuseAuDemarrage() throws IOException {
		Path ailleurs = Files.createDirectory(temp.resolve("ailleurs"));
		Path autreRacine = Files.createDirectory(temp.resolve("piege"));
		Files.createSymbolicLink(autreRacine.resolve("originals"), ailleurs);

		assertThatThrownBy(() -> new StockageImages(autreRacine))
				.isInstanceOf(UncheckedIOException.class);
	}

	@Test
	void suppressionLimiteeAuxFichiersDeLImageEtIdempotente() throws IOException {
		FichiersImage a = stockage.ecrire(octets("a"), FormatImage.JPEG, octets("a2"));
		FichiersImage b = stockage.ecrire(octets("b"), FormatImage.JPEG, octets("b2"));
		Path etranger = Files.write(racine.resolve("originals").resolve("notes.txt"), octets("sans rapport"));

		stockage.supprimer(a);

		assertThat(racine.resolve("originals").resolve(a.referenceOriginale())).doesNotExist();
		assertThat(racine.resolve("optimized").resolve(a.referenceOptimisee())).doesNotExist();
		assertThat(racine.resolve("originals").resolve(b.referenceOriginale())).hasContent("b");
		assertThat(racine.resolve("optimized").resolve(b.referenceOptimisee())).hasContent("b2");
		assertThat(etranger).hasContent("sans rapport");

		stockage.supprimer(a); // déjà absent : sans effet ni erreur
		assertThat(contenu(racine.resolve("originals"))).hasSize(2);
	}

	@Test
	void suppressionImpossibleNeLevePasDErreur() throws IOException {
		FichiersImage fichiers = stockage.ecrire(octets("a"), FormatImage.PNG, octets("a2"));
		Path optimisees = racine.resolve("optimized");
		assumeTrue(Files.getFileStore(optimisees).supportsFileAttributeView("posix"));
		Files.setPosixFilePermissions(optimisees, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			assumeTrue(!Files.isWritable(optimisees), "sans objet pour un super-utilisateur");
			stockage.supprimer(fichiers);
			assertThat(racine.resolve("originals").resolve(fichiers.referenceOriginale())).doesNotExist();
			assertThat(optimisees.resolve(fichiers.referenceOptimisee())).exists();
		} finally {
			Files.setPosixFilePermissions(optimisees, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		stockage.supprimer(fichiers);
		assertThat(optimisees.resolve(fichiers.referenceOptimisee())).doesNotExist();
	}
}
