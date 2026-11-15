package dev.hestia.web.form;

import dev.hestia.web.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Seuls les champs ci-dessous peuvent etre lies depuis un formulaire (jamais l'entite directement). */
@Getter
@Setter
public class UtilisateurForm {

    @NotBlank(message = "Le nom est obligatoire")
    @Size(min = 2, max = 50, message = "Le nom doit contenir entre 2 et 50 caractères")
    private String nom;

    @NotBlank(message = "L'email est obligatoire")
    @Email(message = "L'email n'est pas valide")
    @Size(max = 255, message = "L'email ne peut pas dépasser 255 caractères")
    private String email;

    /** Obligatoire à la création, facultatif à la modification (vide = inchangé). */
    @Pattern(regexp = "^$|.{8,72}", message = "Le mot de passe doit contenir entre 8 et 72 caractères")
    private String motDePasse;

    @NotNull(message = "Le rôle est obligatoire")
    private Role role;
}
