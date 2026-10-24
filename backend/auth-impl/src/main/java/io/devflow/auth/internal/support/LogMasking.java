package io.devflow.auth.internal.support;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.mail.MailSendException;

/**
 * Helpers that keep PII out of log output.
 */
public final class LogMasking {

    private static final int MAX_FAILURE_PARTS = 8;
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private LogMasking() {
    }

    /**
     * Masks the local part of an email address, e.g. {@code tien@gmail.com -> t***@gmail.com}.
     */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * Truncates a client IP for storage without identifying the person: IPv4 keeps the /24 ({@code 203.0.113.0}),
     * IPv6 the /48 ({@code 2001:db8:85a3::}). Returns null for anything that is not an IP literal.
     */
    public static String maskIp(String ip) {
        if (ip == null) {
            return null;
        }
        if (IPV4.matcher(ip).matches()) {
            return ip.substring(0, ip.lastIndexOf('.')) + ".0";
        }
        if (ip.indexOf(':') >= 0 && IPV6_CHARS.matcher(ip).matches()) {
            try {
                // An IPv6 literal is parsed locally; no DNS lookup happens.
                byte[] bytes = InetAddress.getByName(ip).getAddress();
                if (bytes.length == 16) {
                    return String.format("%x:%x:%x::", ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff),
                            ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff), ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff));
                }
            } catch (UnknownHostException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Describes a failure by exception types and SMTP reply codes only, e.g.
     * {@code MailSendException <- SMTPAddressFailedException(smtp=550)}.
     *
     * <p>Exception messages are deliberately left out: mail providers echo recipient addresses in them
     * (e.g. {@code 550 <someone@example.com>: Recipient address rejected}). Walks the cause chain and the
     * per-message exceptions nested in a {@link MailSendException}.
     */
    public static String describeFailure(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        List<String> parts = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty() && parts.size() < MAX_FAILURE_PARTS) {
            Throwable current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            Integer code = smtpReturnCode(current);
            parts.add(current.getClass().getSimpleName() + (code != null ? "(smtp=" + code + ")" : ""));
            if (current instanceof MailSendException mailFailure) {
                pending.addAll(List.of(mailFailure.getMessageExceptions()));
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
        }
        return String.join(" <- ", parts);
    }

    /**
     * SMTP reply code of the provider-specific exceptions ({@code SMTPSendFailedException},
     * {@code SMTPAddressFailedException}, ...), read reflectively so no mail implementation is hard-wired.
     */
    private static Integer smtpReturnCode(Throwable failure) {
        try {
            Method method = failure.getClass().getMethod("getReturnCode");
            Object code = method.invoke(failure);
            return code instanceof Integer value ? value : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
