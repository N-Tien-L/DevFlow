package io.devflow.auth.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The string's UTF-8 encoding must not exceed {@link #value()} bytes ({@code null} is valid).
 *
 * <p>Used on passwords: BCrypt only accepts 72 bytes, and {@code @Size} counts characters, so a 40-character
 * password of accented letters would otherwise pass validation and fail inside the encoder with a 500.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    /** BCrypt's input limit. */
    int BCRYPT_MAX = 72;

    int value();

    String message() default "Value is too long";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
