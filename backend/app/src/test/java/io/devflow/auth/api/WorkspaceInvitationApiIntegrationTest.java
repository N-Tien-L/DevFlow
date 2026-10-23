package io.devflow.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.auth.internal.verification.VerificationCleanupJob;
import io.devflow.auth.internal.workspace.InvitationEmailRequestedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-007: workspace creation and member invitations through the public REST API, on PostgreSQL 17 with the
 * Flyway schema (including the partial unique index on open invitations).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.model.image=none",
        "spring.ai.model.moderation=none",
        "spring.ai.model.audio.speech=none",
        "spring.ai.model.audio.transcription=none",
        "devflow.workspace.max-owned-per-user=3"
})
@AutoConfigureMockMvc
@RecordApplicationEvents
@Testcontainers
class WorkspaceInvitationApiIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private VerificationCleanupJob cleanupJob;

    @Autowired
    private ApplicationEvents applicationEvents;

    @MockitoBean
    private JavaMailSender mailSender;

    private TestUser owner;
    private UUID workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        owner = activeUser("owner");
        workspaceId = createWorkspace(owner, "Team Workspace");
    }

    // --- Workspace creation ---

    @Test
    void createWorkspaceMakesTheCallerOwnerAndListsIt() throws Exception {
        TestUser user = activeUser("creator");
        double before = counter("devflow.auth.workspace_created", "success");

        String body = createWorkspaceRequest(user, Map.of("name", "  <b>Dự án</b>   DevFlow  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Dự án DevFlow"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.slug").value(matchesPattern("du-an-devflow-[a-z0-9]{6}")))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/v1/auth/workspaces").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].role").value("OWNER"));
        assertThat(jdbc.queryForObject("SELECT owner_id FROM workspaces WHERE id = ?", UUID.class, id))
                .isEqualTo(user.id());
        assertThat(jdbc.queryForObject(
                "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?", String.class,
                id, user.id())).isEqualTo("OWNER");
        assertThat(counter("devflow.auth.workspace_created", "success")).isEqualTo(before + 1);
    }

    @Test
    void createWorkspaceValidatesTheNameWithProblemDetails() throws Exception {
        TestUser user = activeUser("validator");

        createWorkspaceRequest(user, Map.of("name", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/problem+json"))
                .andExpect(jsonPath("$.errors.name").exists());
        createWorkspaceRequest(user, Map.of("name", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        createWorkspaceRequest(user, Map.of("name", "<b></b>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        mockMvc.perform(post("/api/v1/auth/workspaces").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspaces WHERE owner_id = ?", Long.class, user.id()))
                .isZero();
    }

    @Test
    void createWorkspaceRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/auth/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Anonymous"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createWorkspaceEnforcesOwnershipQuotaThenPerUserRateLimit() throws Exception {
        TestUser user = activeUser("quota");
        for (int i = 0; i < 3; i++) {
            createWorkspaceRequest(user, Map.of("name", "Workspace " + i)).andExpect(status().isCreated());
        }

        createWorkspaceRequest(user, Map.of("name", "One too many"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_LIMIT_REACHED"));
        createWorkspaceRequest(user, Map.of("name", "Still too many")).andExpect(status().isConflict());
        createWorkspaceRequest(user, Map.of("name", "Throttled"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.title").value("Too Many Requests"));

        // The limit is per user: another user is unaffected.
        createWorkspaceRequest(activeUser("other"), Map.of("name", "Fresh")).andExpect(status().isCreated());
    }

    // --- Invitations ---

    @Test
    void ownerInvitesByEmailStoringOnlyTheTokenHashAndEmailingAfterCommit() throws Exception {
        invite(owner, workspaceId, Map.of("email", "New.Person@Example.TEST", "role", "member"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new.person@example.test"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                .andExpect(jsonPath("$.invitedBy").value(owner.id().toString()))
                .andExpect(jsonPath("$.token").doesNotExist());

        InvitationEmailRequestedEvent event = lastInvitationEmail();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT token_hash, expires_at FROM workspace_invitations WHERE id = ?", event.invitationId());
        assertThat((String) row.get("token_hash")).matches("[0-9a-f]{64}").doesNotContain(event.rawToken());
        Instant expiresAt = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofDays(7).minusMinutes(5),
                Duration.ofDays(7));
        verify(mailSender, timeout(5000).atLeastOnce()).send(any(MimeMessage.class));
    }

    @Test
    void inviteRespondsIdenticallyWhetherOrNotTheEmailHasAnAccount() throws Exception {
        TestUser registered = activeUser("registered");

        JsonNode forRegistered = readJson(invite(owner, workspaceId, Map.of("email", registered.email()))
                .andExpect(status().isCreated()));
        JsonNode forUnknown = readJson(invite(owner, workspaceId, Map.of("email", "nobody-" + UUID.randomUUID()
                + "@example.test")).andExpect(status().isCreated()));

        List<String> registeredFields = new ArrayList<>();
        forRegistered.fieldNames().forEachRemaining(registeredFields::add);
        List<String> unknownFields = new ArrayList<>();
        forUnknown.fieldNames().forEachRemaining(unknownFields::add);
        assertThat(registeredFields).isEqualTo(unknownFields);
        assertThat(forRegistered.get("status").asText()).isEqualTo(forUnknown.get("status").asText());
    }

    @Test
    void onlyOwnersAndAdminsCanManageInvitations() throws Exception {
        TestUser admin = activeUser("admin");
        TestUser member = activeUser("member");
        TestUser outsider = activeUser("outsider");
        addMember(workspaceId, admin, "ADMIN");
        addMember(workspaceId, member, "MEMBER");

        invite(admin, workspaceId, Map.of("email", "by-admin@example.test")).andExpect(status().isCreated());
        invite(admin, workspaceId, Map.of("email", "admin-by-admin@example.test", "role", "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_PERMISSION_DENIED"));
        invite(owner, workspaceId, Map.of("email", "admin-by-owner@example.test", "role", "ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        for (TestUser denied : List.of(member, outsider)) {
            invite(denied, workspaceId, Map.of("email", "x@example.test"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("WORKSPACE_PERMISSION_DENIED"));
            mockMvc.perform(get("/api/v1/auth/workspaces/{id}/invitations", workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(denied)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/v1/auth/workspaces/{id}/invitations/{inv}", workspaceId, UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, bearer(denied)))
                    .andExpect(status().isForbidden());
        }
        // A workspace id that does not exist is indistinguishable from one the caller cannot manage.
        invite(owner, UUID.randomUUID(), Map.of("email", "x@example.test")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/auth/workspaces/{id}/invitations", "not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inviteValidatesInput() throws Exception {
        invite(owner, workspaceId, Map.of("email", "not-an-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
        invite(owner, workspaceId, Map.of("email", "ok@example.test", "role", "OWNER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.role").exists());
    }

    @Test
    void secondOpenInvitationForTheSameEmailIsRejectedButAnExpiredOneIsReplaced() throws Exception {
        invite(owner, workspaceId, Map.of("email", "twice@example.test")).andExpect(status().isCreated());
        UUID first = lastInvitationEmail().invitationId();

        invite(owner, workspaceId, Map.of("email", "TWICE@example.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_PENDING"));

        expire(first);
        invite(owner, workspaceId, Map.of("email", "twice@example.test")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_invitations WHERE workspace_id = ? AND email = 'twice@example.test'",
                Long.class, workspaceId)).isEqualTo(1);
    }

    @Test
    void invitedUserAcceptsOnceAndJoinsWithTheInvitedRole() throws Exception {
        TestUser invitee = activeUser("invitee");
        invite(owner, workspaceId, Map.of("email", invitee.email())).andExpect(status().isCreated());
        String token = lastInvitationEmail().rawToken();

        accept(invitee, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workspaceId.toString()))
                .andExpect(jsonPath("$.role").value("MEMBER"));
        mockMvc.perform(get("/api/v1/auth/workspaces").header(HttpHeaders.AUTHORIZATION, bearer(invitee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(workspaceId.toString())));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM workspace_invitations WHERE token_hash = encode(sha256(?::bytea), 'hex')",
                String.class, token)).isEqualTo("ACCEPTED");

        accept(invitee, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void acceptWithADifferentAccountIsRejectedAndLeavesTheInvitationUsable() throws Exception {
        TestUser invitee = activeUser("rightful");
        TestUser intruder = activeUser("intruder");
        invite(owner, workspaceId, Map.of("email", invitee.email())).andExpect(status().isCreated());
        String token = lastInvitationEmail().rawToken();

        accept(intruder, token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_EMAIL_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_members WHERE user_id = ?", Long.class,
                intruder.id())).isZero();

        accept(invitee, token).andExpect(status().isOk());
    }

    @Test
    void expiredAndRevokedInvitationsCannotBeAccepted() throws Exception {
        TestUser late = activeUser("late");
        invite(owner, workspaceId, Map.of("email", late.email())).andExpect(status().isCreated());
        InvitationEmailRequestedEvent expired = lastInvitationEmail();
        expire(expired.invitationId());

        accept(late, expired.rawToken())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));

        TestUser revokedUser = activeUser("revoked");
        invite(owner, workspaceId, Map.of("email", revokedUser.email())).andExpect(status().isCreated());
        InvitationEmailRequestedEvent revoked = lastInvitationEmail();
        mockMvc.perform(delete("/api/v1/auth/workspaces/{id}/invitations/{inv}", workspaceId, revoked.invitationId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNoContent());

        accept(revokedUser, revoked.rawToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        accept(revokedUser, "not-a-real-token").andExpect(status().isBadRequest());
        accept(revokedUser, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.token").exists());
    }

    @Test
    void listShowsOnlyOpenInvitationsAndRevokeIsScopedToTheWorkspace() throws Exception {
        UUID otherWorkspace = createWorkspace(owner, "Other Workspace");
        invite(owner, workspaceId, Map.of("email", "keep@example.test")).andExpect(status().isCreated());
        invite(owner, workspaceId, Map.of("email", "revoke@example.test")).andExpect(status().isCreated());
        UUID toRevoke = lastInvitationEmail().invitationId();
        invite(owner, workspaceId, Map.of("email", "stale@example.test")).andExpect(status().isCreated());
        expire(lastInvitationEmail().invitationId());
        invite(owner, otherWorkspace, Map.of("email", "elsewhere@example.test")).andExpect(status().isCreated());
        UUID foreign = lastInvitationEmail().invitationId();

        // An invitation id from another workspace is not found through this workspace (IDOR).
        mockMvc.perform(delete("/api/v1/auth/workspaces/{id}/invitations/{inv}", workspaceId, foreign)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/auth/workspaces/{id}/invitations/{inv}", workspaceId, toRevoke)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/auth/workspaces/{id}/invitations/{inv}", workspaceId, toRevoke)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/auth/workspaces/{id}/invitations", workspaceId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("keep@example.test"))
                .andExpect(jsonPath("$[*].email", not(hasItem("elsewhere@example.test"))));
        assertThat(jdbc.queryForObject("SELECT status FROM workspace_invitations WHERE id = ?", String.class,
                foreign)).isEqualTo("PENDING");
    }

    @Test
    void acceptIsRateLimitedPerUser() throws Exception {
        TestUser guesser = activeUser("guesser");
        for (int i = 0; i < 10; i++) {
            accept(guesser, "guess-" + i).andExpect(status().isBadRequest());
        }
        accept(guesser, "guess-final")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void acceptRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/auth/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", "anything"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cleanupJobPurgesStaleInvitationsOnly() throws Exception {
        invite(owner, workspaceId, Map.of("email", "fresh@example.test")).andExpect(status().isCreated());
        UUID fresh = lastInvitationEmail().invitationId();
        invite(owner, workspaceId, Map.of("email", "ancient@example.test")).andExpect(status().isCreated());
        UUID ancient = lastInvitationEmail().invitationId();
        jdbc.update("UPDATE workspace_invitations SET expires_at = now() - interval '8 days' WHERE id = ?", ancient);

        cleanupJob.purge();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_invitations WHERE id = ?", Long.class,
                ancient)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_invitations WHERE id = ?", Long.class,
                fresh)).isEqualTo(1);
    }

    // --- Concurrency & membership edge cases ---

    @Test
    void concurrentCreatesCannotOvershootTheOwnershipQuota() throws Exception {
        TestUser user = activeUser("racer");

        List<Integer> statuses = concurrently(List.of(
                () -> statusOf(createWorkspaceRequest(user, Map.of("name", "Race 1"))),
                () -> statusOf(createWorkspaceRequest(user, Map.of("name", "Race 2"))),
                () -> statusOf(createWorkspaceRequest(user, Map.of("name", "Race 3"))),
                () -> statusOf(createWorkspaceRequest(user, Map.of("name", "Race 4"))),
                () -> statusOf(createWorkspaceRequest(user, Map.of("name", "Race 5")))));

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspaces WHERE owner_id = ?", Long.class, user.id()))
                .isEqualTo(3);
    }

    @Test
    void concurrentAcceptsOfOneTokenSucceedExactlyOnce() throws Exception {
        TestUser invitee = activeUser("double-click");
        invite(owner, workspaceId, Map.of("email", invitee.email())).andExpect(status().isCreated());
        String token = lastInvitationEmail().rawToken();

        List<Integer> statuses = concurrently(List.of(
                () -> statusOf(accept(invitee, token)),
                () -> statusOf(accept(invitee, token))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?", Long.class,
                workspaceId, invitee.id())).isEqualTo(1);
    }

    @Test
    void acceptRacingRevokeHasExactlyOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            TestUser invitee = activeUser("contested-" + round);
            invite(owner, workspaceId, Map.of("email", invitee.email())).andExpect(status().isCreated());
            InvitationEmailRequestedEvent invitation = lastInvitationEmail();

            List<Integer> statuses = concurrently(List.of(
                    () -> statusOf(accept(invitee, invitation.rawToken())),
                    () -> statusOf(mockMvc.perform(delete("/api/v1/auth/workspaces/{id}/invitations/{inv}",
                                    workspaceId, invitation.invitationId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(owner))))));

            long members = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?", Long.class,
                    workspaceId, invitee.id());
            String finalStatus = jdbc.queryForObject("SELECT status FROM workspace_invitations WHERE id = ?",
                    String.class, invitation.invitationId());
            if (statuses.get(0) == 200) {
                assertThat(statuses.get(1)).isEqualTo(404);
                assertThat(finalStatus).isEqualTo("ACCEPTED");
                assertThat(members).isEqualTo(1);
            } else {
                assertThat(statuses).containsExactly(400, 204);
                assertThat(finalStatus).isEqualTo("REVOKED");
                assertThat(members).isZero();
            }
        }
    }

    @Test
    void acceptingWhileAlreadyAMemberKeepsTheCurrentRole() throws Exception {
        TestUser admin = activeUser("existing-admin");
        addMember(workspaceId, admin, "ADMIN");
        invite(owner, workspaceId, Map.of("email", admin.email(), "role", "MEMBER")).andExpect(status().isCreated());
        InvitationEmailRequestedEvent invitation = lastInvitationEmail();

        accept(admin, invitation.rawToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertThat(jdbc.queryForObject(
                "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?", String.class,
                workspaceId, admin.id())).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT status FROM workspace_invitations WHERE id = ?", String.class,
                invitation.invitationId())).isEqualTo("ACCEPTED");
    }

    // --- Helpers ---

    /**
     * Runs the calls on separate threads released at the same instant; returns results in call order.
     */
    private static List<Integer> concurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static int statusOf(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private record TestUser(UUID id, String email) {}

    private TestUser activeUser(String label) {
        UUID id = UUID.randomUUID();
        String email = label + "-" + id + "@devflow.test";
        jdbc.update("INSERT INTO users(id, email, password_hash, full_name, status, email_verified_at) "
                + "VALUES (?, ?, 'test-hash', ?, 'ACTIVE', now())", id, email, "T-007 " + label);
        return new TestUser(id, email);
    }

    private void addMember(UUID workspace, TestUser user, String role) {
        jdbc.update("INSERT INTO workspace_members(workspace_id, user_id, role) VALUES (?, ?, ?)",
                workspace, user.id(), role);
    }

    private void expire(UUID invitationId) {
        jdbc.update("UPDATE workspace_invitations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                invitationId);
    }

    private UUID createWorkspace(TestUser user, String name) throws Exception {
        JsonNode body = readJson(createWorkspaceRequest(user, Map.of("name", name)).andExpect(status().isCreated()));
        return UUID.fromString(body.get("id").asText());
    }

    private ResultActions createWorkspaceRequest(TestUser user, Object body) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/workspaces")
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions invite(TestUser user, UUID workspace, Object body) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/workspaces/{id}/invitations", workspace)
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions accept(TestUser user, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/invitations/accept")
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token))));
    }

    private InvitationEmailRequestedEvent lastInvitationEmail() {
        return applicationEvents.stream(InvitationEmailRequestedEvent.class)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private double counter(String name, String result) {
        Counter counter = meterRegistry.find(name).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private String bearer(TestUser user) {
        return "Bearer " + tokenProvider.generateAccessToken(user.id(), user.email());
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
