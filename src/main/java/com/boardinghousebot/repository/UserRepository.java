package com.boardinghousebot.repository;

import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByTelegramId(Long telegramId);

    @Query("""
        SELECT u
        FROM User u
        LEFT JOIN FETCH u.room
        WHERE u.telegramId = :telegramId
    """)
    Optional<User> findByTelegramIdWithRoom(@Param("telegramId") Long telegramId);

    boolean existsByTelegramId(Long telegramId);

    List<User> findByRole(Role role);

    @Query("""
        SELECT u
        FROM User u
        LEFT JOIN FETCH u.room
        WHERE u.role = :role
    """)
    List<User> findByRoleWithRoom(@Param("role") Role role);

    List<User> findByUsernameContainingIgnoreCase(String username);

    List<User> findByFullNameContainingIgnoreCase(String fullName);

    long countByRoomId(Long roomId);

    List<User> findByRoomId(Long roomId);
}
