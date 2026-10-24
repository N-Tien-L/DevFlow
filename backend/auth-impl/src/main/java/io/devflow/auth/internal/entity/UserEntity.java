package io.devflow.auth.internal.entity;

import io.devflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA entity representing a registered user account in the system.
 */
@Entity
@Table(name = "users")
public class UserEntity extends BaseEntity {

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    protected UserEntity() {
        // Required by JPA
    }

    public UserEntity(String email, String passwordHash, String fullName, String avatarUrl) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.avatarUrl = avatarUrl;
    }

    public UserEntity(String email, String passwordHash, String fullName) {
        this(email, passwordHash, fullName, null);
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    /**
     * Version embedded in every JWT issued to this user (claim {@code tv}); tokens carrying another value are
     * rejected.
     */
    public int getTokenVersion() {
        return tokenVersion;
    }

    /**
     * Replaces the password and bumps the token version, so every JWT issued before this call stops being accepted.
     */
    public void changePassword(String newPasswordHash, Instant changedAt) {
        this.passwordHash = newPasswordHash;
        this.passwordChangedAt = changedAt;
        this.tokenVersion++;
    }

    /**
     * Marks the email address as verified and the account as usable for login.
     */
    public void activate(Instant verifiedAt) {
        this.status = UserStatus.ACTIVE;
        this.emailVerifiedAt = verifiedAt;
    }
}
