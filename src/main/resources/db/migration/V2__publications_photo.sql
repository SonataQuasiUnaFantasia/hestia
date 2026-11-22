-- Publications photo. Tables nouvelles uniquement : aucune donnee existante n'est modifiee.
-- CREATE TABLE sans IF NOT EXISTS : en cas de collision de nom avec une table du projet "api"
-- (qui possede deja IMAGE et IMAGE_METADATA), la migration doit echouer plutot qu'adopter une table etrangere.

CREATE TABLE PUBLICATION (
    id BIGINT NOT NULL AUTO_INCREMENT,
    auteur_id BIGINT NOT NULL,
    texte VARCHAR(280) NULL,
    date_creation DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_publication_auteur FOREIGN KEY (auteur_id) REFERENCES UTILISATEUR (id)
);

-- Fil trie du plus recent au plus ancien.
CREATE INDEX idx_publication_date_creation ON PUBLICATION (date_creation, id);

-- Pas de ON DELETE CASCADE : la suppression passe par l'application, qui journalise d'abord
-- les fichiers a effacer (voir PUBLICATION_FICHIER_A_NETTOYER). Les octets restent sur disque.
CREATE TABLE PUBLICATION_IMAGE (
    id BIGINT NOT NULL AUTO_INCREMENT,
    publication_id BIGINT NOT NULL,
    reference_originale VARCHAR(64) NOT NULL,
    reference_optimisee VARCHAR(64) NOT NULL,
    format_original VARCHAR(10) NOT NULL,
    largeur INT NOT NULL,
    hauteur INT NOT NULL,
    orientation VARCHAR(10) NOT NULL,
    position_initiale INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_publication_image_publication FOREIGN KEY (publication_id) REFERENCES PUBLICATION (id),
    CONSTRAINT uk_publication_image_position UNIQUE (publication_id, position_initiale),
    CONSTRAINT uk_publication_image_originale UNIQUE (reference_originale),
    CONSTRAINT uk_publication_image_optimisee UNIQUE (reference_optimisee)
);