package com.chalkline.repo;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.Role;
import com.chalkline.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Sign-in lookup. Email is unique across every organisation. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByCalendarToken(String calendarToken);

    // Everything below is tenant-scoped: an organisation is always supplied,
    // so one institution's queries can never return another's rows.

    List<User> findByOrganisationAndRoleOrderByDisplayNameAsc(Organisation organisation, Role role);

    List<User> findByOrganisationOrderByRoleAscDisplayNameAsc(Organisation organisation);

    Optional<User> findByIdAndOrganisation(Long id, Organisation organisation);

    Optional<User> findByEmailAndOrganisation(String email, Organisation organisation);

    long countByOrganisation(Organisation organisation);

    long countByOrganisationAndRole(Organisation organisation, Role role);
}
