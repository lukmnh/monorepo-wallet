package com.gpay.auth.repository;

import com.gpay.auth.entity.Users;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<Users, UUID> {
    Optional<Users> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}
