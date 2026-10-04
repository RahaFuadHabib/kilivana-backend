package com.kilivana.users.repository;

import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    @EntityGraph(attributePaths = "refreshTokens")
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByRole(UserRole role);

    Optional<User> findByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByUsername(String username);

    boolean existsByPhoneNumberAndIdNot(String phoneNumber, UUID id);

    boolean existsByUsernameAndIdNot(String username, UUID id);

    List<User> findAllByRole(UserRole role);

    List<User> findAllByOrderByCreatedAtDesc();

    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<User> findByRole(UserRole role, Pageable pageable);

    Page<User> findByStatus(UserStatus status, Pageable pageable);

    Page<User> findByRoleAndStatus(UserRole role, UserStatus status, Pageable pageable);

    long countByRole(UserRole role);

    long countByStatus(UserStatus status);

    long countByRoleAndStatus(UserRole role, UserStatus status);
}
