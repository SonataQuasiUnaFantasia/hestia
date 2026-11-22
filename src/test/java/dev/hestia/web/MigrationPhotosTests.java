package dev.hestia.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** V3 : les images des anciennes publications deviennent des photos ; le texte et les anciennes tables disparaissent. */
class MigrationPhotosTests {

	private static final String URL = "jdbc:h2:mem:migration_photos;MODE=MariaDB;DB_CLOSE_DELAY=-1";

	private static Flyway flyway(String cible) {
		return Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration").target(cible).load();
	}

	@Test
	void imagesDesPublicationsRepriseEnPhotos() throws Exception {
		flyway("2").migrate();
		try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement s = c.createStatement()) {
			s.execute("INSERT INTO UTILISATEUR (id, email, nom) VALUES (1, 'a@test', 'Alice')");
			s.execute("INSERT INTO PUBLICATION (id, auteur_id, texte, date_creation) VALUES "
					+ "(10, 1, 'avec texte', '2026-01-01 10:00:00'), (11, 1, NULL, '2026-02-01 10:00:00')");
			s.execute("INSERT INTO PUBLICATION_IMAGE (publication_id, reference_originale, reference_optimisee, "
					+ "format_original, largeur, hauteur, orientation, position_initiale) VALUES "
					+ "(10, 'b.png', 'b.jpg', 'PNG', 20, 30, 'PORTRAIT', 1), "
					+ "(10, 'a.jpg', 'a-opt.jpg', 'JPEG', 30, 20, 'LANDSCAPE', 0), "
					+ "(11, 'c.webp', 'c.jpg', 'WEBP', 40, 40, 'SQUARE', 0)");

			flyway("3").migrate();

			List<String> photos = new ArrayList<>();
			try (ResultSet r = s.executeQuery("SELECT auteur_id, reference_originale, reference_optimisee, "
					+ "format_original, largeur, hauteur, date_creation FROM PHOTO ORDER BY id")) {
				while (r.next()) {
					photos.add(r.getLong(1) + " " + r.getString(2) + " " + r.getString(3) + " " + r.getString(4)
							+ " " + r.getInt(5) + "x" + r.getInt(6) + " " + r.getTimestamp(7).toLocalDateTime());
				}
			}
			// Mêmes fichiers, ordre de sélection conservé, date de la publication d'origine.
			assertThat(photos).containsExactly(
					"1 a.jpg a-opt.jpg JPEG 30x20 2026-01-01T10:00",
					"1 b.png b.jpg PNG 20x30 2026-01-01T10:00",
					"1 c.webp c.jpg WEBP 40x40 2026-02-01T10:00");
			try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
					+ "WHERE TABLE_NAME IN ('PUBLICATION', 'PUBLICATION_IMAGE')")) {
				r.next();
				assertThat(r.getInt(1)).isZero();
			}
		}
	}
}
