package io.devflow.auth.internal.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogMaskingTest {

    @Test
    void maskIpKeepsOnlyTheNetworkPart() {
        assertThat(LogMasking.maskIp("203.0.113.45")).isEqualTo("203.0.113.0");
        assertThat(LogMasking.maskIp("2001:db8:85a3:8d3:1319:8a2e:370:7348")).isEqualTo("2001:db8:85a3::");
        assertThat(LogMasking.maskIp("::1")).isEqualTo("0:0:0::");
    }

    @Test
    void maskIpRejectsAnythingThatIsNotAnIpLiteral() {
        assertThat(LogMasking.maskIp(null)).isNull();
        assertThat(LogMasking.maskIp("")).isNull();
        assertThat(LogMasking.maskIp("localhost")).isNull();
        assertThat(LogMasking.maskIp("example.com:8080")).isNull();
        assertThat(LogMasking.maskIp("203.0.113.45; DROP TABLE users")).isNull();
    }
}
