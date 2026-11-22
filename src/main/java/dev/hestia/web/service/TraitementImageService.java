package dev.hestia.web.service;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import dev.hestia.web.entity.FormatImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.filters.Flip;
import net.coobird.thumbnailator.filters.ImageFilter;
import net.coobird.thumbnailator.filters.Rotation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * Valide une image reçue et produit sa version d'affichage, entièrement en mémoire.
 * Les octets reçus ne sont jamais modifiés : l'orientation EXIF, le redimensionnement et
 * la conversion en JPEG ne concernent que la version optimisée (JPEG, 1080 px au plus).
 */
@Service
public class TraitementImageService {

    /** La version optimisée tient entièrement dans un carré de ce côté, sans agrandissement. */
    public static final int COTE_MAX = 1080;

    private static final float QUALITE_JPEG = 0.85f;
    private static final long ATTENTE_MAX_SECONDES = 30;

    /** Avertissements du décodeur JPEG du JDK qui signalent un fichier tronqué ou des données abîmées. */
    private static final List<String> AVERTISSEMENTS_BLOQUANTS =
            List.of("Truncated File", "Corrupt JPEG data", "Premature end");

    /** Le JPEG n'a pas de transparence : elle est aplatie sur un fond blanc explicite. */
    private static final ImageFilter APLATIR_SUR_BLANC = image -> {
        BufferedImage aplatie = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = aplatie.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, aplatie.getWidth(), aplatie.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return aplatie;
    };

    static {
        // Enregistrement explicite : ne dépend pas de la découverte des plugins ImageIO par le conteneur.
        IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageReaderSpi());
    }

    private final DataSize tailleMax;
    private final int dimensionMax;
    private final long pixelsMax;

    /**
     * @param tailleMax    taille maximale d'un fichier image
     * @param dimensionMax largeur et hauteur maximales acceptées, en pixels
     * @param pixelsMax    nombre total de pixels maximal (plafonné pour borner la mémoire de décodage)
     */
    public TraitementImageService(@Value("${app.upload.max-file-size}") DataSize tailleMax,
                                  @Value("${app.upload.max-dimension}") int dimensionMax,
                                  @Value("${app.upload.max-pixels}") long pixelsMax) {
        if (dimensionMax < 1 || dimensionMax > 30_000 || pixelsMax < 1 || pixelsMax > 100_000_000) {
            throw new IllegalArgumentException("app.upload.max-dimension doit être entre 1 et 30000"
                    + " et app.upload.max-pixels entre 1 et 100000000");
        }
        this.tailleMax = tailleMax;
        this.dimensionMax = dimensionMax;
        this.pixelsMax = pixelsMax;
    }

    /**
     * @param format    format réellement détecté dans le contenu
     * @param optimisee JPEG d'affichage
     * @param largeur   largeur de la version optimisée (après correction EXIF)
     * @param hauteur   hauteur de la version optimisée (après correction EXIF)
     */
    public record ImageTraitee(FormatImage format, byte[] optimisee, int largeur, int hauteur) {
    }

    /**
     * @throws ImageRefuseeException si le fichier est vide, trop volumineux, d'un format non accepté,
     *                               trop grand en pixels, illisible ou corrompu
     */
    public ImageTraitee traiter(byte[] octets) {
        if (octets == null || octets.length == 0) {
            throw new ImageRefuseeException("le fichier est vide");
        }
        if (octets.length > tailleMax.toBytes()) {
            throw new ImageRefuseeException("le fichier dépasse la taille maximale autorisée ("
                    + tailleMax.toMegabytes() + " Mo)");
        }
        FormatImage format = detecterFormat(octets);
        if (format == null) {
            throw new ImageRefuseeException("format non pris en charge (JPEG, PNG ou WebP attendu)");
        }
        return valider(octets, format);
    }

    /** Format d'après la signature du contenu ; le nom et le type MIME fournis par le client sont ignorés. */
    static FormatImage detecterFormat(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return FormatImage.JPEG;
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
            return FormatImage.PNG;
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return FormatImage.WEBP;
        }
        return null;
    }

    private ImageTraitee valider(byte[] octets, FormatImage format) {
        BufferedImage source;
        int largeurSource;
        int hauteurSource;
        ImageReader lecteur = lecteurPour(format);
        List<String> avertissements = new ArrayList<>();
        lecteur.addIIOReadWarningListener((origine, message) -> avertissements.add(message));
        try (ImageInputStream flux = new MemoryCacheImageInputStream(new ByteArrayInputStream(octets))) {
            lecteur.setInput(flux, true, true);
            // Dimensions lues dans l'en-tête, avant tout décodage des pixels.
            largeurSource = lecteur.getWidth(0);
            hauteurSource = lecteur.getHeight(0);
            verifierDimensions(largeurSource, hauteurSource);
            ImageReadParam parametres = lecteur.getDefaultReadParam();
            // Une très grande image est sous-échantillonnée dès le décodage (au moins 2x la taille cible).
            int pas = Math.max(1, Math.max(largeurSource, hauteurSource) / (2 * COTE_MAX));
            parametres.setSourceSubsampling(pas, pas, 0, 0);
            source = lecteur.read(0, parametres);
        } catch (IOException | RuntimeException e) {
            // Les décodeurs lèvent aussi des exceptions non contrôlées sur des données malformées.
            if (e instanceof ImageRefuseeException refus) {
                throw refus;
            }
            throw new ImageRefuseeException("le fichier est illisible ou corrompu", e);
        } finally {
            lecteur.dispose();
        }
        if (source == null || avertissements.stream().anyMatch(TraitementImageService::estBloquant)) {
            throw new ImageRefuseeException("le fichier est illisible ou corrompu");
        }

        int exif = format == FormatImage.JPEG ? orientationExif(octets) : 1;
        int[] cible = dimensionsCibles(largeurSource, hauteurSource);
        boolean quartDeTour = exif >= 5 && exif <= 8; // rotation d'un quart de tour : largeur et hauteur échangées
        int largeur = quartDeTour ? cible[1] : cible[0];
        int hauteur = quartDeTour ? cible[0] : cible[1];

        byte[] optimisee = encoder(source, cible[0], cible[1], exif);
        return new ImageTraitee(format, optimisee, largeur, hauteur);
    }

    private void verifierDimensions(int largeur, int hauteur) {
        if (largeur <= 0 || hauteur <= 0) {
            throw new ImageRefuseeException("le fichier est illisible ou corrompu");
        }
        if (largeur > dimensionMax || hauteur > dimensionMax) {
            throw new ImageRefuseeException("les dimensions de l'image dépassent "
                    + dimensionMax + " pixels de côté");
        }
        if ((long) largeur * hauteur > pixelsMax) {
            throw new ImageRefuseeException("l'image contient trop de pixels ("
                    + pixelsMax / 1_000_000 + " millions au maximum)");
        }
    }

    /** Dimensions de la version optimisée (avant rotation EXIF) : ratio conservé, jamais d'agrandissement. */
    static int[] dimensionsCibles(int largeur, int hauteur) {
        double echelle = Math.min(1.0, (double) COTE_MAX / Math.max(largeur, hauteur));
        return new int[] {Math.max(1, (int) Math.round(largeur * echelle)),
                Math.max(1, (int) Math.round(hauteur * echelle))};
    }

    /** Orientation EXIF (1 à 8) ; 1 (aucune transformation) si elle est absente, illisible ou hors plage. */
    private static int orientationExif(byte[] octets) {
        try {
            ExifIFD0Directory exif = ImageMetadataReader.readMetadata(new ByteArrayInputStream(octets))
                    .getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int valeur = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                return valeur >= 1 && valeur <= 8 ? valeur : 1;
            }
        } catch (Exception | StackOverflowError e) {
            // Métadonnées illisibles : l'image reste valide, sans correction d'orientation.
        }
        return 1;
    }

    private static byte[] encoder(BufferedImage source, int largeur, int hauteur, int exif) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try {
            Thumbnails.of(source)
                    .forceSize(largeur, hauteur) // dimensions déjà calculées avec le bon ratio
                    .addFilters(filtresExif(exif))
                    .addFilter(APLATIR_SUR_BLANC)
                    .outputFormat("jpg")
                    .outputQuality(QUALITE_JPEG)
                    .toOutputStream(sortie);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Échec de génération de la version optimisée", e);
        }
        return sortie.toByteArray();
    }

    /**
     * Transformations redonnant l'orientation visuelle pour chaque valeur EXIF (1 à 8), inversions comprises.
     */
    private static List<ImageFilter> filtresExif(int orientation) {
        return switch (orientation) {
            case 2 -> List.of(Flip.HORIZONTAL);
            case 3 -> List.of(Rotation.ROTATE_180_DEGREES);
            case 4 -> List.of(Flip.VERTICAL);
            case 5 -> List.of(Rotation.RIGHT_90_DEGREES, Flip.HORIZONTAL);
            case 6 -> List.of(Rotation.RIGHT_90_DEGREES);
            case 7 -> List.of(Rotation.LEFT_90_DEGREES, Flip.HORIZONTAL);
            case 8 -> List.of(Rotation.LEFT_90_DEGREES);
            default -> List.of();
        };
    }

    private static ImageReader lecteurPour(FormatImage format) {
        Iterator<ImageReader> lecteurs = ImageIO.getImageReadersByFormatName(format.name());
        if (!lecteurs.hasNext()) {
            throw new IllegalStateException("Aucun décodeur disponible pour " + format);
        }
        return lecteurs.next();
    }

    private static boolean estBloquant(String avertissement) {
        return AVERTISSEMENTS_BLOQUANTS.stream().anyMatch(avertissement::contains);
    }
}
