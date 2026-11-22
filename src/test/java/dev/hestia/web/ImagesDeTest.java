package dev.hestia.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/**
 * Images de test. Motif commun : quatre quadrants de couleurs distinctes (haut-gauche rouge,
 * haut-droite vert, bas-gauche bleu, bas-droite blanc), ce qui rend visible toute rotation ou inversion.
 * Les fichiers de src/test/resources/images (EXIF, WebP) sont produits par generer.py (Pillow).
 */
final class ImagesDeTest {

	private ImagesDeTest() {
	}

	static byte[] jpeg(int largeur, int hauteur) {
		return encoder(quadrants(largeur, hauteur, BufferedImage.TYPE_INT_RGB), "jpg");
	}

	static byte[] png(int largeur, int hauteur) {
		return encoder(quadrants(largeur, hauteur, BufferedImage.TYPE_INT_RGB), "png");
	}

	/** PNG dont le quadrant haut-gauche est entièrement transparent. */
	static byte[] pngTransparent(int largeur, int hauteur) {
		BufferedImage image = quadrants(largeur, hauteur, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < hauteur / 2; y++) {
			for (int x = 0; x < largeur / 2; x++) {
				image.setRGB(x, y, 0x00000000);
			}
		}
		return encoder(image, "png");
	}

	/** PNG uni, très compact même pour de très grandes dimensions (1 bit par pixel). */
	static byte[] pngUni(int largeur, int hauteur) {
		return encoder(new BufferedImage(largeur, hauteur, BufferedImage.TYPE_BYTE_BINARY), "png");
	}

	static byte[] ressource(String nom) {
		try (InputStream flux = ImagesDeTest.class.getResourceAsStream("/images/" + nom)) {
			assertThat(flux).as("ressource de test %s", nom).isNotNull();
			return flux.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static BufferedImage decoder(byte[] octets) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(octets));
			assertThat(image).as("image décodable").isNotNull();
			return image;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Vérifie le motif en quadrants au centre de chaque quadrant et dans les coins. */
	static void assertQuadrants(BufferedImage image, Color hautGauche, Color hautDroite, Color basGauche,
			Color basDroite) {
		int l = image.getWidth();
		int h = image.getHeight();
		assertCouleur(image, l / 4, h / 4, hautGauche);
		assertCouleur(image, 3 * l / 4, h / 4, hautDroite);
		assertCouleur(image, l / 4, 3 * h / 4, basGauche);
		assertCouleur(image, 3 * l / 4, 3 * h / 4, basDroite);
		assertCouleur(image, 1, 1, hautGauche);
		assertCouleur(image, l - 2, 1, hautDroite);
		assertCouleur(image, 1, h - 2, basGauche);
		assertCouleur(image, l - 2, h - 2, basDroite);
	}

	static void assertQuadrantsStandard(BufferedImage image) {
		assertQuadrants(image, Color.RED, Color.GREEN, Color.BLUE, Color.WHITE);
	}

	static void assertCouleur(BufferedImage image, int x, int y, Color attendue) {
		Color reelle = new Color(image.getRGB(x, y));
		int ecart = Math.max(Math.abs(reelle.getRed() - attendue.getRed()),
				Math.max(Math.abs(reelle.getGreen() - attendue.getGreen()),
						Math.abs(reelle.getBlue() - attendue.getBlue())));
		assertThat(ecart).as("couleur en (%d,%d) : %s au lieu de %s", x, y, reelle, attendue).isLessThan(48);
	}

	private static BufferedImage quadrants(int largeur, int hauteur, int type) {
		BufferedImage image = new BufferedImage(largeur, hauteur, type);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, largeur / 2, hauteur / 2);
		g.setColor(Color.GREEN);
		g.fillRect(largeur / 2, 0, largeur - largeur / 2, hauteur / 2);
		g.setColor(Color.BLUE);
		g.fillRect(0, hauteur / 2, largeur / 2, hauteur - hauteur / 2);
		g.setColor(Color.WHITE);
		g.fillRect(largeur / 2, hauteur / 2, largeur - largeur / 2, hauteur - hauteur / 2);
		g.dispose();
		return image;
	}

	private static byte[] encoder(BufferedImage image, String format) {
		try {
			ByteArrayOutputStream sortie = new ByteArrayOutputStream();
			assertThat(ImageIO.write(image, format, sortie)).isTrue();
			return sortie.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
