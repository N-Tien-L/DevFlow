package io.devflow.auth.api;

import io.devflow.auth.api.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for erasing the caller's account. The current password is required again (re-authentication),
 * so a stolen session alone cannot delete the account.
 */
public record DeleteAccountRequest(
        @NotBlank(message = "Password is required")
        // No valid password is longer; checked in bytes so BCrypt never sees an over-long input.
        @MaxUtf8Bytes(value = MaxUtf8Bytes.BCRYPT_MAX, message = "The password is incorrect.")
        String password
) {

    @Override
    public String toString() {
        return "DeleteAccountRequest[password=***]";
    }
}
