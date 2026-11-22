-- CREATE TABLE IF NOT EXISTS : sans effet sur la base actuelle, permet de demarrer sur une base vide.
CREATE TABLE IF NOT EXISTS UTILISATEUR (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) DEFAULT NULL,
    nom VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT EMAIL UNIQUE (email)
);

-- NULL = compte sans mot de passe : il ne peut pas se connecter tant qu'un administrateur n'en definit pas un.
ALTER TABLE UTILISATEUR ADD COLUMN IF NOT EXISTS mot_de_passe VARCHAR(100) NULL;

-- DEFAULT 'USER' : les lignes existantes et les insertions du projet "api" restent valides.
ALTER TABLE UTILISATEUR ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';
