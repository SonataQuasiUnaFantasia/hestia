-- Les publications ne portent plus de texte : chaque image publiée devient une photo autonome.
-- Les images existantes sont reprises (mêmes fichiers sur disque, mêmes références), le texte est abandonné.

CREATE TABLE PHOTO (
    id BIGINT NOT NULL AUTO_INCREMENT,
    auteur_id BIGINT NOT NULL,
    reference_originale VARCHAR(64) NOT NULL,
    reference_optimisee VARCHAR(64) NOT NULL,
    format_original VARCHAR(10) NOT NULL,
    largeur INT NOT NULL,
    hauteur INT NOT NULL,
    date_creation DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_photo_auteur FOREIGN KEY (auteur_id) REFERENCES UTILISATEUR (id),
    CONSTRAINT uk_photo_originale UNIQUE (reference_originale),
    CONSTRAINT uk_photo_optimisee UNIQUE (reference_optimisee)
);

-- Galerie triée de la plus récente à la plus ancienne.
CREATE INDEX idx_photo_date_creation ON PHOTO (date_creation, id);

INSERT INTO PHOTO (auteur_id, reference_originale, reference_optimisee, format_original, largeur, hauteur,
                   date_creation)
SELECT p.auteur_id, i.reference_originale, i.reference_optimisee, i.format_original, i.largeur, i.hauteur,
       p.date_creation
FROM PUBLICATION_IMAGE i
JOIN PUBLICATION p ON p.id = i.publication_id
ORDER BY p.date_creation, p.id, i.position_initiale;

DROP TABLE PUBLICATION_IMAGE;
DROP TABLE PUBLICATION;
