package io.devflow.auth.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import io.devflow.auth.internal.entity.WorkspaceRole;
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
class WorkspaceMemberRepositoryTest {

    @Autowired
    private WorkspaceMemberRepository memberRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private UserEntity owner;
    private UserEntity memberUser;
    private WorkspaceEntity workspace;

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(
            new UserEntity("owner@devflow.io", "pass_hash", "Space Owner")
        );
        memberUser = userRepository.saveAndFlush(
            new UserEntity("member@devflow.io", "pass_hash", "Space Member")
        );
        workspace = workspaceRepository.saveAndFlush(
            new WorkspaceEntity("DevFlow Core", "devflow-core", owner)
        );
    }

    @Test
    @DisplayName("TC-13: Should add and find member by workspaceId and userId")
    void shouldAddAndFindMemberByWorkspaceIdAndUserId() {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity(workspace, memberUser, WorkspaceRole.ADMIN);
        WorkspaceMemberEntity saved = memberRepository.saveAndFlush(member);

        entityManager.clear();

        Optional<WorkspaceMemberEntity> found = memberRepository.findByWorkspaceIdAndUserId(
            workspace.getId(),
            memberUser.getId()
        );

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getRole()).isEqualTo(WorkspaceRole.ADMIN);
        assertThat(found.get().getWorkspace().getId()).isEqualTo(workspace.getId());
        assertThat(found.get().getUser().getId()).isEqualTo(memberUser.getId());
    }

    @Test
    @DisplayName("TC-14: Should return empty Optional when membership does not exist")
    void shouldReturnEmptyWhenMembershipNotFound() {
        Optional<WorkspaceMemberEntity> found = memberRepository.findByWorkspaceIdAndUserId(
            UUID.randomUUID(),
            UUID.randomUUID()
        );

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("TC-15: Should check if workspace membership exists")
    void shouldCheckIfMembershipExists() {
        memberRepository.saveAndFlush(
            new WorkspaceMemberEntity(workspace, memberUser, WorkspaceRole.MEMBER)
        );

        assertThat(memberRepository.existsByWorkspaceIdAndUserId(workspace.getId(), memberUser.getId())).isTrue();
        assertThat(memberRepository.existsByWorkspaceIdAndUserId(workspace.getId(), UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("TC-16: Should find all members by workspace ID")
    void shouldFindAllByWorkspaceId() {
        UserEntity user2 = userRepository.saveAndFlush(
            new UserEntity("user2@devflow.io", "pass_hash", "User 2")
        );

        memberRepository.saveAndFlush(new WorkspaceMemberEntity(workspace, owner, WorkspaceRole.OWNER));
        memberRepository.saveAndFlush(new WorkspaceMemberEntity(workspace, memberUser, WorkspaceRole.ADMIN));
        memberRepository.saveAndFlush(new WorkspaceMemberEntity(workspace, user2, WorkspaceRole.MEMBER));

        List<WorkspaceMemberEntity> members = memberRepository.findAllByWorkspaceId(workspace.getId());

        assertThat(members).hasSize(3);
        assertThat(members)
            .extracting(WorkspaceMemberEntity::getRole)
            .containsExactlyInAnyOrder(WorkspaceRole.OWNER, WorkspaceRole.ADMIN, WorkspaceRole.MEMBER);
    }

    @Test
    @DisplayName("TC-17: Should find all memberships by user ID across workspaces")
    void shouldFindAllByUserId() {
        WorkspaceEntity workspace2 = workspaceRepository.saveAndFlush(
            new WorkspaceEntity("DevFlow Web", "devflow-web", owner)
        );

        memberRepository.saveAndFlush(new WorkspaceMemberEntity(workspace, memberUser, WorkspaceRole.MEMBER));
        memberRepository.saveAndFlush(new WorkspaceMemberEntity(workspace2, memberUser, WorkspaceRole.ADMIN));

        List<WorkspaceMemberEntity> memberships = memberRepository.findAllByUserId(memberUser.getId());

        assertThat(memberships).hasSize(2);
        assertThat(memberships)
            .extracting(m -> m.getWorkspace().getSlug())
            .containsExactlyInAnyOrder("devflow-core", "devflow-web");
    }

    @Test
    @DisplayName("TC-18: Should throw DataIntegrityViolationException when duplicate (workspace, user) is added")
    void shouldThrowWhenDuplicateWorkspaceMember() {
        memberRepository.saveAndFlush(
            new WorkspaceMemberEntity(workspace, memberUser, WorkspaceRole.MEMBER)
        );

        WorkspaceMemberEntity duplicate = new WorkspaceMemberEntity(workspace, memberUser, WorkspaceRole.ADMIN);

        assertThatThrownBy(() -> {
            memberRepository.saveAndFlush(duplicate);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("TC-19: Should persist role enum values correctly")
    void shouldPersistRoleEnumValues() {
        WorkspaceMemberEntity memberOwner = memberRepository.saveAndFlush(
            new WorkspaceMemberEntity(workspace, owner, WorkspaceRole.OWNER)
        );

        entityManager.clear();

        Optional<WorkspaceMemberEntity> reloaded = memberRepository.findById(memberOwner.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getRole()).isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    @DisplayName("TC-20: Should populate JPA auditing timestamps on persist")
    void shouldPopulateAuditingTimestamps() {
        Instant beforeSave = Instant.now().minusSeconds(1);

        WorkspaceMemberEntity member = new WorkspaceMemberEntity(workspace, memberUser, WorkspaceRole.MEMBER);
        WorkspaceMemberEntity saved = memberRepository.saveAndFlush(member);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isAfterOrEqualTo(beforeSave);
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(beforeSave);
    }
}
