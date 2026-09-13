package com.chalkline.repo;

import com.chalkline.domain.Course;
import com.chalkline.domain.Organisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {

    List<Course> findByOrganisationOrderByCodeAsc(Organisation organisation);

    Optional<Course> findByOrganisationAndCodeIgnoreCase(Organisation organisation, String code);

    Optional<Course> findByIdAndOrganisation(Long id, Organisation organisation);

    long countByOrganisation(Organisation organisation);
}
