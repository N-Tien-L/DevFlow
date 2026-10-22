package io.devflow.auth.internal.antiabuse;

import java.util.Hashtable;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MX lookup through the JDK DNS provider, using the host's configured resolvers.
 */
@Component
public class JndiMxLookup implements MxLookup {

    private static final Logger log = LoggerFactory.getLogger(JndiMxLookup.class);

    private final EmailDomainProperties properties;

    public JndiMxLookup(EmailDomainProperties properties) {
        this.properties = properties;
    }

    @Override
    public Result lookup(String asciiDomain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", "dns:");
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(properties.getMxLookupTimeout().toMillis()));
        env.put("com.sun.jndi.dns.timeout.retries", "1");

        DirContext context = null;
        try {
            context = new InitialDirContext(env);
            Attributes attributes = context.getAttributes(asciiDomain, new String[] {"MX"});
            Attribute mx = attributes.get("MX");
            if (mx == null) {
                return Result.NO_MX;
            }
            NamingEnumeration<?> records = mx.getAll();
            while (records.hasMore()) {
                // Record format: "<preference> <exchange>"; a null MX is "0 ." (RFC 7505).
                String[] parts = String.valueOf(records.next()).trim().split("\\s+");
                String exchange = parts[parts.length - 1];
                if (!exchange.isEmpty() && !".".equals(exchange)) {
                    return Result.HAS_MX;
                }
            }
            return Result.NO_MX;
        } catch (NameNotFoundException e) {
            return Result.NO_DOMAIN;
        } catch (NamingException e) {
            log.warn("MX lookup failed for {}: {}", asciiDomain, e.getClass().getSimpleName());
            return Result.UNKNOWN;
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (NamingException ignored) {
                    // Closing a DNS context only releases provider state; nothing to recover.
                }
            }
        }
    }
}
