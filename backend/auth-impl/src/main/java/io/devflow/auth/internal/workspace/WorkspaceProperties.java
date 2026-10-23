package io.devflow.auth.internal.workspace;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workspace and invitation limits. Bound to prefix {@code devflow.workspace}.
 */
@Component
@ConfigurationProperties(prefix = "devflow.workspace")
public class WorkspaceProperties {

    /**
     * Maximum number of workspaces a single user may own.
     */
    private int maxOwnedPerUser = 10;

    /**
     * Lifetime of an emailed invitation link.
     */
    private Duration invitationTtl = Duration.ofDays(7);

    /**
     * Maximum number of open (pending, unexpired) invitations per workspace.
     */
    private int maxPendingInvitations = 100;

    /**
     * Expired or revoked invitations older than this are purged (they hold the invitee's email).
     */
    private Duration invitationRetention = Duration.ofDays(7);

    public int getMaxOwnedPerUser() {
        return maxOwnedPerUser;
    }

    public void setMaxOwnedPerUser(int maxOwnedPerUser) {
        this.maxOwnedPerUser = maxOwnedPerUser;
    }

    public Duration getInvitationTtl() {
        return invitationTtl;
    }

    public void setInvitationTtl(Duration invitationTtl) {
        this.invitationTtl = invitationTtl;
    }

    public int getMaxPendingInvitations() {
        return maxPendingInvitations;
    }

    public void setMaxPendingInvitations(int maxPendingInvitations) {
        this.maxPendingInvitations = maxPendingInvitations;
    }

    public Duration getInvitationRetention() {
        return invitationRetention;
    }

    public void setInvitationRetention(Duration invitationRetention) {
        this.invitationRetention = invitationRetention;
    }
}
