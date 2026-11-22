# Hestia

Application web développée avec Spring Boot et MariaDB.

## Prérequis

- Java 21
- MariaDB

## Installation

### 1. Créer la base de données

Démarrer MariaDB et exécuter :

```sql
CREATE DATABASE hestia;
```

### 2. Configurer la connexion

Créer le fichier `src/main/resources/application-local.properties` :

```properties
spring.datasource.username=votre_utilisateur
spring.datasource.password=votre_mot_de_passe
```

Remplacer les valeurs par les identifiants MariaDB.

### 3. Configurer l'administrateur

Définir les variables d'environnement suivantes :

- `APP_ADMIN_EMAIL` : email du premier administrateur.
- `APP_ADMIN_PASSWORD` : mot de passe de 8 à 72 caractères.
- `APP_ADMIN_NOM` : nom de l'administrateur (facultatif, défaut : `Administrateur`).
- `APP_UPLOAD_DIRECTORY` : dossier de stockage des photos (facultatif, défaut : `~/hestia/uploads`).

Le premier administrateur est créé automatiquement au démarrage si aucun administrateur n'existe.

### 4. Lancer l'application et exécuter les tests

Lancer les tests :

```bash
./mvnw test
```

Démarrer l'application :

```bash
./mvnw spring-boot:run
```