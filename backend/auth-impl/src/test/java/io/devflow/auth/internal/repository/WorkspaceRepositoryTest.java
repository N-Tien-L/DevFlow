package io.devflow.auth.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkspaceRepositoryTest {

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private UserEntity owner;

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(
            new UserEntity("owner@devflow.io", "pass_hash", "Workspace Owner")
        );
    }

    @Test
    @DisplayName("TC-06: Should save and find workspace by slug")
    void shouldSaveAndFindWorkspaceBySlug() {
        WorkspaceEntity workspace = new WorkspaceEntity("Engineering Team", "eng-team", owner);
        WorkspaceEntity saved = workspaceRepository.saveAndFlush(workspace);

        entityManager.clear();

        Optional<WorkspaceEntity> found = workspaceRepository.findBySlug("eng-team");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getName()).isEqualTo("Engineering Team");
        assertThat(found.get().getSlug()).isEqualTo("eng-team");
        assertThat(found.get().getOwner().getId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("TC-07: Should return empty Optional when slug is not found")
    void shouldReturnEmptyWhenSlugNotFound() {
        Optional<WorkspaceEntity> found = workspaceRepository.findBySlug("non-existent-slug");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("TC-08: Should check if slug exists")
    void shouldCheckIfSlugExists() {
        WorkspaceEntity workspace = new WorkspaceEntity("Product Team", "product-team", owner);
        workspaceRepository.saveAndFlush(workspace);

        assertThat(workspaceRepository.existsBySlug("product-team")).isTrue();
        assertThat(workspaceRepository.existsBySlug("marketing-team")).isFalse();
    }

    @Test
    @DisplayName("TC-09: Should find all workspaces by owner ID")
    void shouldFindAllByOwnerId() {
        UserEntity otherUser = userRepository.saveAndFlush(
            new UserEntity("other@devflow.io", "pass_hash", "Other User")
        );

        workspaceRepository.saveAndFlush(new WorkspaceEntity("Workspace 1", "ws-1", owner));
        workspaceRepository.saveAndFlush(new WorkspaceEntity("Workspace 2", "ws-2", owner));
        workspaceRepository.saveAndFlush(new WorkspaceEntity("Workspace 3", "ws-3", otherUser));

        List<WorkspaceEntity> ownerWorkspaces = workspaceRepository.findAllByOwnerId(owner.getId());

        assertThat(ownerWorkspaces).hasSize(2);
        assertThat(ownerWorkspaces)
            .extracting(WorkspaceEntity::getSlug)
            .containsExactlyInAnyOrder("ws-1", "ws-2");
    }

    @Test
    @DisplayName("TC-10: Should return empty list when owner has no workspaces")
    void shouldReturnEmptyListWhenOwnerHasNoWorkspaces() {
        List<WorkspaceEntity> workspaces = workspaceRepository.findAllByOwnerId(UUID.randomUUID());

        assertThat(workspaces).isEmpty();
    }

    @Test
    @DisplayName("TC-11: Should throw DataIntegrityViolationException when slug is duplicated")
    void shouldThrowWhenDuplicateSlug() {
        WorkspaceEntity ws1 = new WorkspaceEntity("First Space", "shared-slug", owner);
        workspaceRepository.saveAndFlush(ws1);

        WorkspaceEntity ws2 = new WorkspaceEntity("Second Space", "shared-slug", owner);

        assertThatThrownBy(() -> {
            workspaceRepository.saveAndFlush(ws2);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("TC-12: Should populate JPA auditing timestamps on persist")
    void shouldPopulateAuditingTimestamps() {
        Instant beforeSave = Instant.now().minusSeconds(1);

        WorkspaceEntity workspace = new WorkspaceEntity("Audit Workspace", "audit-ws", owner);
        WorkspaceEntity saved = workspaceRepository.saveAndFlush(workspace);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isAfterOrEqualTo(beforeSave);
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(beforeSave);
    }
}
