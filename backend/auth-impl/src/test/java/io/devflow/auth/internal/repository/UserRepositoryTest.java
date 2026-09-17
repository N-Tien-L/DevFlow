package io.devflow.auth.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.devflow.auth.internal.entity.UserEntity;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("TC-01: Should save and find user by email")
    void shouldSaveAndFindUserByEmail() {
        UserEntity user = new UserEntity(
            "dev@devflow.io",
            "hashed_secret_123",
            "DevFlow Developer",
            "https://devflow.io/avatar.png"
        );

        UserEntity saved = userRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        Optional<UserEntity> found = userRepository.findByEmail("dev@devflow.io");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getEmail()).isEqualTo("dev@devflow.io");
        assertThat(found.get().getPasswordHash()).isEqualTo("hashed_secret_123");
        assertThat(found.get().getFullName()).isEqualTo("DevFlow Developer");
        assertThat(found.get().getAvatarUrl()).isEqualTo("https://devflow.io/avatar.png");
    }

    @Test
    @DisplayName("TC-02: Should return empty Optional when email is not found")
    void shouldReturnEmptyWhenEmailNotFound() {
        Optional<UserEntity> found = userRepository.findByEmail("unknown@devflow.io");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("TC-03: Should check if email exists")
    void shouldCheckIfEmailExists() {
        UserEntity user = new UserEntity("alice@devflow.io", "pass_hash", "Alice Developer");
        userRepository.saveAndFlush(user);

        assertThat(userRepository.existsByEmail("alice@devflow.io")).isTrue();
        assertThat(userRepository.existsByEmail("bob@devflow.io")).isFalse();
    }

    @Test
    @DisplayName("TC-04: Should throw DataIntegrityViolationException when email is duplicated")
    void shouldThrowWhenDuplicateEmail() {
        UserEntity user1 = new UserEntity("duplicate@devflow.io", "hash1", "User One");
        userRepository.saveAndFlush(user1);

        UserEntity user2 = new UserEntity("duplicate@devflow.io", "hash2", "User Two");

        assertThatThrownBy(() -> {
            userRepository.saveAndFlush(user2);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("TC-05: Should populate JPA auditing timestamps on persist")
    void shouldPopulateAuditingTimestamps() {
        Instant beforeSave = Instant.now().minusSeconds(1);

        UserEntity user = new UserEntity("audit@devflow.io", "hash", "Audit User");
        UserEntity saved = userRepository.saveAndFlush(user);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isAfterOrEqualTo(beforeSave);
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(beforeSave);
    }
}
