package dev.hestia.web.repository;

import dev.hestia.web.entity.Photo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    /** De la plus récente à la plus ancienne ; l'auteur est chargé avec la photo. */
    @EntityGraph(attributePaths = "auteur")
    Slice<Photo> findAllByOrderByDateCreationDescIdDesc(Pageable pageable);
}
