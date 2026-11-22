package dev.hestia.web;

import static dev.hestia.web.ImagesDeTest.assertCouleur;
import static dev.hestia.web.ImagesDeTest.assertQuadrantsStandard;
import static dev.hestia.web.ImagesDeTest.decoder;
import static dev.hestia.web.ImagesDeTest.jpeg;
import static dev.hestia.web.ImagesDeTest.png;
import static dev.hestia.web.ImagesDeTest.pngTransparent;
import static dev.hestia.web.ImagesDeTest.pngUni;
import static dev.hestia.web.ImagesDeTest.ressource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hestia.web.service.ImageRefuseeException;
import dev.hestia.web.service.TraitementImageService;
import dev.hestia.web.service.TraitementImageService.ImageTraitee;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

/** Traitement réel des images : de vrais fichiers JPEG, PNG et WebP sont décodés, optimisés puis relus. */
class TraitementImageTests {

	/** Limites réduites pour tester les refus sans fabriquer d'images gigantesques. */
	private final TraitementImageService service = new TraitementImageService(
			DataSize.ofKilobytes(600), 3000, 4_000_000);

	// --- Formats ---

	@Test
	void jpegAccepteEtOptimise() {
		byte[] original = jpeg(800, 600);
		ImageTraitee resultat = service.traiter(original);
		assertThat(resultat.largeur()).isEqualTo(800);
		assertThat(resultat.hauteur()).isEqualTo(600);
		assertEstUnJpeg(resultat.optimisee());
		assertQuadrantsStandard(decoder(resultat.optimisee()));
	}

	@Test
	void pngAccepteEtConvertiEnJpeg() {
		ImageTraitee resultat = service.traiter(png(640, 480));
		assertEstUnJpeg(resultat.optimisee());
		assertQuadrantsStandard(decoder(resultat.optimisee()));
	}

	@Test
	void webpAvecPertesReellementDecode() {
		ImageTraitee resultat = service.traiter(ressource("paysage.webp")); // 1600x900, VP8
		assertThat(resultat.largeur()).isEqualTo(1080);
		assertThat(resultat.hauteur()).isEqualTo(608);
		assertEstUnJpeg(resultat.optimisee());
		assertQuadrantsStandard(decoder(resultat.optimisee()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"transparent-lossless.webp", "transparent-lossy.webp"})
	void webpTransparentAplatiSurBlanc(String fichier) { // VP8L et VP8X avec alpha, 300x200
		ImageTraitee resultat = service.traiter(ressource(fichier));
		BufferedImage optimisee = decoder(resultat.optimisee());
		assertThat(optimisee.getWidth()).isEqualTo(300);
		assertThat(optimisee.getHeight()).isEqualTo(200);
		ImagesDeTest.assertQuadrants(optimisee, Color.WHITE, Color.GREEN, Color.BLUE, Color.WHITE);
	}

	// --- Redimensionnement ---

	@ParameterizedTest
	@CsvSource({
			"2400, 1600, 1080, 720", // grand paysage
			"1600, 2400, 720, 1080", // grand portrait
			"2000, 2000, 1080, 1080", // grand carré
			"1080, 1080, 1080, 1080", // exactement la taille cible
			"1081, 500, 1080, 500", // à peine trop large
			"3000, 300, 1080, 108", // panoramique
	})
	void grandeImageReduiteDansUnCarreDe1080SansDeformation(int l, int h, int largeurAttendue,
			int hauteurAttendue) {
		ImageTraitee resultat = service.traiter(jpeg(l, h));
		assertThat(resultat.largeur()).isEqualTo(largeurAttendue);
		assertThat(resultat.hauteur()).isEqualTo(hauteurAttendue);
		BufferedImage optimisee = decoder(resultat.optimisee());
		assertThat(optimisee.getWidth()).isEqualTo(largeurAttendue);
		assertThat(optimisee.getHeight()).isEqualTo(hauteurAttendue);
		assertThat(Math.max(optimisee.getWidth(), optimisee.getHeight())).isLessThanOrEqualTo(1080);
		// Ratio conservé à l'arrondi d'un pixel près, image entière (les quatre quadrants sont présents).
		assertThat((double) optimisee.getWidth() / optimisee.getHeight())
				.isCloseTo((double) l / h, org.assertj.core.data.Offset.offset(0.011 * l / h));
		assertQuadrantsStandard(optimisee);
	}

	@ParameterizedTest
	@CsvSource({"400, 300", "300, 400", "250, 250", "1, 1", "1080, 2"})
	void petiteImageJamaisAgrandie(int l, int h) {
		ImageTraitee resultat = service.traiter(png(l, h));
		assertThat(resultat.largeur()).isEqualTo(l);
		assertThat(resultat.hauteur()).isEqualTo(h);
		BufferedImage optimisee = decoder(resultat.optimisee());
		assertThat(optimisee.getWidth()).isEqualTo(l);
		assertThat(optimisee.getHeight()).isEqualTo(h);
	}

	@Test
	void dimensionsAnnonceesEgalesAuxDimensionsReellesDeLaVersionOptimisee() {
		ImageTraitee presqueCarree = service.traiter(pngUni(2000, 1999));
		BufferedImage optimisee = decoder(presqueCarree.optimisee());
		assertThat(presqueCarree.largeur()).isEqualTo(optimisee.getWidth()).isEqualTo(1080);
		assertThat(presqueCarree.hauteur()).isEqualTo(optimisee.getHeight()).isEqualTo(1079);
	}

	@Test
	void versionOptimiseeDUnPoidsRaisonnable() {
		byte[] original = png(2400, 1600);
		ImageTraitee resultat = service.traiter(original);
		assertThat(resultat.optimisee().length).isLessThan(200_000);
	}

	// --- Transparence ---

	@Test
	void transparencePngAplatieSurFondBlancEtNonNoir() {
		ImageTraitee resultat = service.traiter(pngTransparent(400, 300));
		BufferedImage optimisee = decoder(resultat.optimisee());
		assertThat(optimisee.getColorModel().hasAlpha()).isFalse();
		ImagesDeTest.assertQuadrants(optimisee, Color.WHITE, Color.GREEN, Color.BLUE, Color.WHITE);
	}

	// --- Données reçues ---

	@Test
	void octetsRecusNonModifies() {
		for (byte[] original : new byte[][] {jpeg(2000, 1500), pngTransparent(300, 300),
				ressource("exif-6.jpg"), ressource("paysage.webp")}) {
			byte[] copie = original.clone();
			ImageTraitee resultat = service.traiter(original);
			assertThat(original).isEqualTo(copie);
			assertThat(resultat.optimisee()).isNotEqualTo(original);
		}
	}

	// --- Orientation EXIF ---

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
	void orientationExifRedresseeRotationsEtInversions(int orientation) {
		// Chaque fichier affiche le même visuel 96x64 ; pour 5 à 8 les pixels sont stockés en 64x96.
		byte[] original = ressource("exif-" + orientation + ".jpg");
		BufferedImage brute = decoder(original);
		assertThat(brute.getWidth()).isEqualTo(orientation >= 5 ? 64 : 96);

		ImageTraitee resultat = service.traiter(original);

		assertThat(resultat.largeur()).isEqualTo(96);
		assertThat(resultat.hauteur()).isEqualTo(64);
		BufferedImage optimisee = decoder(resultat.optimisee());
		assertThat(optimisee.getWidth()).isEqualTo(96);
		assertThat(optimisee.getHeight()).isEqualTo(64);
		// Une double rotation ou une inversion manquante déplacerait les couleurs.
		assertQuadrantsStandard(optimisee);
	}

	@Test
	void grandJpegPivoteDevientPortraitApresCorrectionExif() {
		// Pixels stockés en paysage 1600x1200, orientation 6 : le visuel est un portrait 1200x1600.
		byte[] original = ressource("exif-6-grand.jpg");
		BufferedImage brute = decoder(original);
		assertThat(brute.getWidth()).isEqualTo(1600);
		assertThat(brute.getHeight()).isEqualTo(1200);

		ImageTraitee resultat = service.traiter(original);

		assertThat(resultat.largeur()).isEqualTo(810);
		assertThat(resultat.hauteur()).isEqualTo(1080);
		BufferedImage optimisee = decoder(resultat.optimisee());
		assertThat(optimisee.getWidth()).isEqualTo(810);
		assertThat(optimisee.getHeight()).isEqualTo(1080);
		assertQuadrantsStandard(optimisee);
	}

	@Test
	void jpegSansExifLaisseTelQuel() {
		ImageTraitee resultat = service.traiter(ressource("sans-exif.jpg"));
		assertThat(resultat.largeur()).isEqualTo(96);
		assertThat(resultat.hauteur()).isEqualTo(64);
		assertQuadrantsStandard(decoder(resultat.optimisee()));
	}

	@Test
	void orientationExifHorsPlageIgnoree() {
		ImageTraitee resultat = service.traiter(ressource("exif-invalide.jpg")); // valeur 9
		assertThat(resultat.largeur()).isEqualTo(96);
		assertThat(resultat.hauteur()).isEqualTo(64);
		assertQuadrantsStandard(decoder(resultat.optimisee()));
	}

	@Test
	void segmentExifIllisibleIgnoreSansEchec() {
		// Même fichier que exif-6.jpg mais en-tête TIFF détruit : aucune rotation ne doit être appliquée.
		ImageTraitee resultat = service.traiter(ressource("exif-corrompu.jpg"));
		assertThat(resultat.largeur()).isEqualTo(64);
		assertThat(resultat.hauteur()).isEqualTo(96);
		BufferedImage optimisee = decoder(resultat.optimisee());
		// Pixels stockés (non pivotés) de l'orientation 6 : le visuel tourné d'un quart de tour à gauche.
		ImagesDeTest.assertQuadrants(optimisee, Color.GREEN, Color.WHITE, Color.RED, Color.BLUE);
	}

	// --- Refus ---

	@Test
	void fichierVideRefuse() {
		assertRefus(new byte[0], "vide");
		assertRefus(null, "vide");
	}

	@Test
	void contenuQuiNEstPasUneImageAccepteeRefuse() {
		assertRefus("ceci n'est pas une image".getBytes(StandardCharsets.UTF_8), "format non pris en charge");
		assertRefus("GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1), "format non pris en charge");
		assertRefus("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8),
				"format non pris en charge");
		assertRefus(new byte[] {'B', 'M', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, "format non pris en charge");
	}

	@Test
	void signatureValideMaisContenuIllisibleRefuse() {
		byte[] fauxJpeg = new byte[2000];
		fauxJpeg[0] = (byte) 0xFF;
		fauxJpeg[1] = (byte) 0xD8;
		fauxJpeg[2] = (byte) 0xFF;
		assertRefus(fauxJpeg, "illisible ou corrompu");
		byte[] fauxPng = Arrays.copyOf(png(50, 50), 20);
		assertRefus(fauxPng, "illisible ou corrompu");
		byte[] fauxWebp = "RIFF\u0010\u0000\u0000\u0000WEBPVP8 xxxxxxxx".getBytes(StandardCharsets.ISO_8859_1);
		assertRefus(fauxWebp, "illisible ou corrompu");
	}

	@Test
	void fichiersTronquesRefuses() {
		byte[] jpeg = jpeg(800, 600);
		// Le décodeur JPEG du JDK ne lève pas d'exception sur un fichier tronqué : il faut lire ses avertissements.
		assertRefus(Arrays.copyOf(jpeg, jpeg.length / 2), "illisible ou corrompu");
		assertRefus(Arrays.copyOf(jpeg, jpeg.length - 2), "illisible ou corrompu");
		byte[] png = png(800, 600);
		assertRefus(Arrays.copyOf(png, png.length / 2), "illisible ou corrompu");
		byte[] webp = ressource("paysage.webp");
		assertRefus(Arrays.copyOf(webp, webp.length / 2), "illisible ou corrompu");
		byte[] webpSansPertes = ressource("transparent-lossless.webp");
		assertRefus(Arrays.copyOf(webpSansPertes, webpSansPertes.length / 2), "illisible ou corrompu");
	}

	@Test
	void donneesAbimeesRefusees() {
		byte[] jpeg = jpeg(800, 600);
		for (int i = jpeg.length / 2; i < jpeg.length / 2 + 60; i++) {
			jpeg[i] ^= 0x5A;
		}
		assertRefus(jpeg, "illisible ou corrompu");
		byte[] png = png(800, 600);
		for (int i = png.length / 2; i < png.length / 2 + 60; i++) {
			png[i] ^= 0x5A;
		}
		assertRefus(png, "illisible ou corrompu");
	}

	@Test
	void fichierTropVolumineuxRefuse() {
		byte[] enorme = Arrays.copyOf(jpeg(100, 100), 600 * 1024 + 1);
		assertRefus(enorme, "taille maximale");
	}

	@Test
	void dimensionsExcessivesRefusees() {
		assertRefus(pngUni(3001, 10), "dimensions");
		assertRefus(pngUni(10, 3001), "dimensions");
		assertThat(service.traiter(pngUni(3000, 10)).largeur()).isEqualTo(1080);
	}

	@Test
	void nombreDePixelsExcessifRefuse() {
		assertRefus(pngUni(2500, 2000), "trop de pixels"); // 5 Mpx, chaque côté sous la limite
		assertThat(service.traiter(pngUni(2000, 2000)).largeur()).isEqualTo(1080); // 4 Mpx : accepté
	}

	@Test
	void dimensionsAnnonceesParLEnTeteControleesAvantDecodage() {
		// En-tête PNG falsifié annonçant 60000x60000 : refusé d'après l'en-tête, sans allouer les pixels.
		byte[] png = png(10, 10);
		png[16] = 0;
		png[17] = 0;
		png[18] = (byte) 0xEA;
		png[19] = 0x60;
		png[20] = 0;
		png[21] = 0;
		png[22] = (byte) 0xEA;
		png[23] = 0x60;
		assertThatThrownBy(() -> service.traiter(png)).isInstanceOf(ImageRefuseeException.class);
	}

	private void assertRefus(byte[] octets, String motif) {
		assertThatThrownBy(() -> service.traiter(octets))
				.isInstanceOf(ImageRefuseeException.class)
				.hasMessageContaining(motif);
	}

	private static void assertEstUnJpeg(byte[] octets) {
		assertThat(octets[0] & 0xFF).isEqualTo(0xFF);
		assertThat(octets[1] & 0xFF).isEqualTo(0xD8);
		assertThat(octets[octets.length - 2] & 0xFF).isEqualTo(0xFF);
		assertThat(octets[octets.length - 1] & 0xFF).isEqualTo(0xD9);
		assertCouleur(decoder(octets), 0, 0, new Color(decoder(octets).getRGB(0, 0)));
	}
}
