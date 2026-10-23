package io.devflow.auth.internal.antiabuse;

/**
 * Resolves whether a domain publishes mail exchangers. Kept separate from {@link EmailDomainValidator}
 * so the validator can be tested without network access.
 */
public interface MxLookup {

    enum Result {
        /** At least one usable MX record. */
        HAS_MX,
        /** Domain exists but publishes no MX record, or only a null MX ("0 .", RFC 7505). */
        NO_MX,
        /** Domain does not exist (NXDOMAIN). */
        NO_DOMAIN,
        /** DNS timed out or failed; the answer is unknown. */
        UNKNOWN
    }

    Result lookup(String asciiDomain);
}
