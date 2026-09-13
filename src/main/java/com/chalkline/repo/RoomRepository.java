package com.chalkline.repo;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByOrganisationOrderByNameAsc(Organisation organisation);

    Optional<Room> findByOrganisationAndNameIgnoreCase(Organisation organisation, String name);

    Optional<Room> findByIdAndOrganisation(Long id, Organisation organisation);

    long countByOrganisation(Organisation organisation);
}
