package dev.hestia.web;

import static dev.hestia.web.ImagesDeTest.decoder;
import static dev.hestia.web.ImagesDeTest.jpeg;
import static dev.hestia.web.ImagesDeTest.ressource;
import static org.assertj.core.api.Assertions.assertThat;

import dev.hestia.web.entity.Photo;
import dev.hestia.web.entity.Role;
import dev.hestia.web.entity.Utilisateur;
import dev.hestia.web.repository.PhotoRepository;
import dev.hestia.web.repository.UtilisateurRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Parcours complet sur un vrai serveur Tomcat avec les requêtes qu'émet le script d'envoi par lot :
 * une requête multipart par image, jeton CSRF en en-tête, réponse JSON ; limites multipart du conteneur.
 * (MockMvc ne passe ni par l'analyse multipart de Tomcat ni par ses limites de taille.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"app.upload.directory=target/test-uploads-http",
		"app.upload.max-file-size=300KB",
		"spring.servlet.multipart.max-request-size=400KB"})
@ActiveProfiles("test")
class PhotoHttpTests {

	private static final String MOT_DE_PASSE = "motdepasse-http";
	private static final Pattern CSRF_FORMULAIRE = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
	private static final Pattern CSRF_META = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");
	private static final Pattern EN_TETE_META = Pattern.compile("<meta name=\"_csrf_header\" content=\"([^\"]+)\"");

	@Value("${local.server.port}") int port;
	@Autowired UtilisateurRepository utilisateurRepository;
	@Autowired PhotoRepository photoRepository;
	@Autowired PasswordEncoder encoder;

	private final List<Utilisateur> comptes = new ArrayList<>();
	private HttpClient client;

	/** Jeton et nom d'en-tête lus dans la page d'envoi, comme le fait le script. */
	private record Jeton(String enTete, String valeur) {
	}

	@BeforeEach
	void preparer() throws IOException {
		viderStockage();
		compte("alice@http.test", "Alice");
		compte("bob@http.test", "Bob");
		client = nouveauClient();
	}

	@AfterEach
	void nettoyer() throws IOException {
		photoRepository.deleteAll();
		utilisateurRepository.deleteAll(comptes);
		viderStockage();
	}

	private void compte(String email, String nom) {
		Utilisateur u = new Utilisateur();
		u.setEmail(email);
		u.setNom(nom);
		u.setRole(Role.USER);
		u.setMotDePasse(encoder.encode(MOT_DE_PASSE));
		comptes.add(utilisateurRepository.save(u));
	}

	private static HttpClient nouveauClient() {
		return HttpClient.newBuilder().cookieHandler(new CookieManager())
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	private URI url(String chemin) {
		return URI.create("http://localhost:" + port + chemin);
	}

	private HttpResponse<String> get(HttpClient c, String chemin) throws Exception {
		return c.send(HttpRequest.newBuilder(url(chemin)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String extraire(Pattern motif, String html) {
		Matcher m = motif.matcher(html);
		assertThat(m.find()).as("motif %s présent dans la page", motif).isTrue();
		return m.group(1);
	}

	private void connecter(HttpClient c, String email) throws Exception {
		String jeton = extraire(CSRF_FORMULAIRE, get(c, "/login").body());
		String corps = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
				+ "&motDePasse=" + MOT_DE_PASSE + "&_csrf=" + URLEncoder.encode(jeton, StandardCharsets.UTF_8);
		HttpResponse<String> reponse = c.send(HttpRequest.newBuilder(url("/login"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(corps)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(reponse.statusCode()).isEqualTo(302);
		assertThat(reponse.headers().firstValue("Location").orElse("")).doesNotContain("error");
	}

	private Jeton jetonDeLaPageDEnvoi(HttpClient c) throws Exception {
		HttpResponse<String> page = get(c, "/photos/envoi");
		assertThat(page.statusCode()).isEqualTo(200);
		return new Jeton(extraire(EN_TETE_META, page.body()), extraire(CSRF_META, page.body()));
	}

	/** Requête identique à celle du script : FormData avec une seule partie « image », jeton en en-tête. */
	private HttpResponse<String> envoyer(HttpClient c, Jeton jeton, String nom, byte[] octets) throws Exception {
		String frontiere = "----HestiaTest" + System.nanoTime();
		ByteArrayOutputStream corps = new ByteArrayOutputStream();
		corps.write(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"" + nom
				+ "\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		corps.write(octets);
		corps.write(("\r\n--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpRequest.Builder requete = HttpRequest.newBuilder(url("/photos"))
				.header("Accept", "application/json")
				.header("Content-Type", "multipart/form-data; boundary=" + frontiere)
				.POST(HttpRequest.BodyPublishers.ofByteArray(corps.toByteArray()));
		if (jeton != null) {
			requete.header(jeton.enTete(), jeton.valeur());
		}
		return c.send(requete.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static Path stockage(String nom) {
		return Path.of("target/test-uploads-http").toAbsolutePath().resolve(nom);
	}

	private static List<Path> fichiers(String nom) throws IOException {
		if (!Files.isDirectory(stockage(nom))) {
			return List.of();
		}
		try (Stream<Path> contenu = Files.list(stockage(nom))) {
			return contenu.toList();
		}
	}

	private static void viderStockage() throws IOException {
		for (String nom : List.of("originals", "optimized")) {
			for (Path fichier : fichiers(nom)) {
				Files.delete(fichier);
			}
		}
	}

	private void assertAucuneTrace() throws IOException {
		assertThat(photoRepository.count()).isZero();
		assertThat(fichiers("originals")).isEmpty();
		assertThat(fichiers("optimized")).isEmpty();
	}

	@Test
	void parcoursCompletEnvoiParLotConsultationSuppression() throws Exception {
		connecter(client, "alice@http.test");
		Jeton jeton = jetonDeLaPageDEnvoi(client);

		// Lot de trois images envoyées l'une après l'autre ; la deuxième est refusée sans bloquer la suite.
		byte[] webp = ressource("paysage.webp");
		HttpResponse<String> premiere = envoyer(client, jeton, "paysage.webp", webp);
		HttpResponse<String> refusee = envoyer(client, jeton, "faux.jpg", "pas une image".getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> troisieme = envoyer(client, jeton, "exif.jpg", ressource("exif-6.jpg"));

		assertThat(premiere.statusCode()).isEqualTo(201);
		assertThat(premiere.body()).matches("\\{\"id\":\\d+}");
		assertThat(refusee.statusCode()).isEqualTo(400);
		assertThat(refusee.body()).contains("\"erreur\"").contains("format non pris en charge");
		assertThat(troisieme.statusCode()).isEqualTo(201);

		List<Photo> photos = photoRepository.findAll();
		assertThat(photos).hasSize(2);
		assertThat(fichiers("optimized")).hasSize(2);
		assertThat(fichiers("originals")).anySatisfy(f -> assertThat(Files.readAllBytes(f)).isEqualTo(webp));

		// Galerie : la plus récente d'abord.
		HttpResponse<String> galerie = get(client, "/photos");
		assertThat(galerie.statusCode()).isEqualTo(200);
		Long idPremiere = Long.valueOf(premiere.body().replaceAll("\\D", ""));
		Long idTroisieme = Long.valueOf(troisieme.body().replaceAll("\\D", ""));
		assertThat(galerie.body().indexOf("/photos/" + idTroisieme + "/image"))
				.isPositive().isLessThan(galerie.body().indexOf("/photos/" + idPremiere + "/image"));

		// Image servie : JPEG décodable, type MIME cohérent, non interprétable autrement.
		HttpResponse<byte[]> image = client.send(HttpRequest.newBuilder(url("/photos/" + idPremiere + "/image"))
				.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(image.statusCode()).isEqualTo(200);
		assertThat(image.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/jpeg");
		assertThat(image.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
		assertThat(decoder(image.body()).getWidth()).isEqualTo(1080);

		// Un autre utilisateur voit la photo mais ne peut pas la supprimer, même en forgeant la requête.
		HttpClient clientBob = nouveauClient();
		connecter(clientBob, "bob@http.test");
		assertThat(get(clientBob, "/photos").body()).contains("/photos/" + idPremiere + "/image")
				.doesNotContain("/supprimer");
		Jeton jetonBob = jetonDeLaPageDEnvoi(clientBob);
		HttpResponse<String> refus = clientBob.send(HttpRequest.newBuilder(url("/photos/" + idPremiere + "/supprimer"))
				.header(jetonBob.enTete(), jetonBob.valeur()).POST(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(refus.statusCode()).isEqualTo(403);
		assertThat(photoRepository.count()).isEqualTo(2);

		// Sans session : ni page, ni image.
		assertThat(get(nouveauClient(), "/photos/" + idPremiere + "/image").statusCode()).isEqualTo(302);

		// L'auteur supprime par le formulaire affiché dans la galerie.
		String jetonSuppression = extraire(CSRF_FORMULAIRE, galerie.body());
		HttpResponse<String> suppression = client.send(HttpRequest.newBuilder(
						url("/photos/" + idPremiere + "/supprimer"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("_csrf=" + jetonSuppression)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(suppression.statusCode()).isEqualTo(302);
		assertThat(photoRepository.findById(idPremiere)).isEmpty();
		assertThat(fichiers("optimized")).hasSize(1);
	}

	@Test
	void envoiSansJetonCsrfRefuse() throws Exception {
		connecter(client, "alice@http.test");
		assertThat(envoyer(client, null, "a.jpg", jpeg(100, 100)).statusCode()).isEqualTo(403);
		assertAucuneTrace();
	}

	@Test
	void envoiSansSessionRefuse() throws Exception {
		// Le script ne prend pour un succès qu'un 201 accompagné d'un identifiant.
		HttpResponse<String> reponse = envoyer(client, new Jeton("X-CSRF-TOKEN", "inconnu"), "a.jpg", jpeg(100, 100));
		assertThat(reponse.statusCode()).isNotEqualTo(201);
		assertAucuneTrace();
	}

	@Test
	void fichierDepassantLaLimiteMultipartDuConteneurRefuseEn413() throws Exception {
		connecter(client, "alice@http.test");
		Jeton jeton = jetonDeLaPageDEnvoi(client);
		byte[] tropGros = Arrays.copyOf(jpeg(100, 100), 350 * 1024); // limite configurée ici : 300 Ko

		assertThat(envoyer(client, jeton, "gros.jpg", tropGros).statusCode()).isEqualTo(413);
		assertAucuneTrace();
	}

	@Test
	void fichierTresVolumineuxRecoitUneReponseClaireEtNonUneConnexionCoupee() throws Exception {
		// Bien au-delà des 2 Mo que Tomcat accepte de lire par défaut après un refus : sans
		// server.tomcat.max-swallow-size, le client ne recevrait pas de réponse (connexion coupée).
		connecter(client, "alice@http.test");
		Jeton jeton = jetonDeLaPageDEnvoi(client);
		byte[] enorme = Arrays.copyOf(jpeg(100, 100), 12 * 1024 * 1024);

		assertThat(envoyer(client, jeton, "enorme.jpg", enorme).statusCode()).isEqualTo(413);
		assertAucuneTrace();
	}
}
