package io.devflow.board.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.board.internal.repository.*;
import io.devflow.common.event.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;

class GitTransitionGuardTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final BoardRepository boards = mock(BoardRepository.class);
    final TaskRepository tasks = mock(TaskRepository.class);
    final ColumnRepository columns = mock(ColumnRepository.class);
    final TaskMoveExecutor moves = mock(TaskMoveExecutor.class);

    GitTaskTransitionService service(boolean enabled) {
        return new GitTaskTransitionService(boards, tasks, columns, moves, jdbc,
                new ObjectMapper(), new SimpleMeterRegistry(), enabled);
    }

    static Stream<GitTaskTransition> invalid() {
        UUID id = UUID.randomUUID();
        return Stream.of(
                new GitTaskTransition(null,id,id,id,EventTypes.GIT_PR_MERGED,Instant.now(),"url","repo",null,null),
                new GitTaskTransition(id,null,id,id,EventTypes.GIT_PR_MERGED,Instant.now(),"url","repo",null,null),
                new GitTaskTransition(id,id,null,id,EventTypes.GIT_PR_MERGED,Instant.now(),"url","repo",null,null),
                new GitTaskTransition(id,id,id,null,EventTypes.GIT_PR_MERGED,Instant.now(),"url","repo",null,null),
                new GitTaskTransition(id,id,id,id,EventTypes.GIT_PR_MERGED,null,"url","repo",null,null),
                new GitTaskTransition(id,id,id,id,"unsupported",Instant.now(),"url","repo",null,null));
    }

    @ParameterizedTest @MethodSource("invalid")
    void missingProvenanceNeverReachesPersistence(GitTaskTransition event) {
        assertThatThrownBy(() -> service(true).apply(event)).hasMessage("INVALID_EVENT");
        verifyNoInteractions(jdbc,boards,tasks,columns,moves);
    }

    @Test void disabledConsumerDefersInsteadOfAcknowledging() {
        UUID id=UUID.randomUUID();
        var request=new GitTaskTransition(id,id,id,id,EventTypes.GIT_COMMIT_LINKED,Instant.now(),"sha","repo",null,null);
        assertThatThrownBy(() -> service(false).apply(request)).hasMessage("DISABLED");
        verifyNoInteractions(jdbc,boards,tasks,columns,moves);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void metricFailureNeverChangesTheResultAndMdcIsRestored(boolean reject) {
        var service=mock(GitTaskTransitionService.class);
        var metrics=mock(io.micrometer.core.instrument.MeterRegistry.class);
        when(metrics.counter(anyString(),any(String[].class))).thenThrow(new IllegalStateException("metric unavailable"));
        when(service.apply(any())).thenAnswer(invocation->{
            assertThat(org.slf4j.MDC.get("userId")).isNull();
            assertThat(org.slf4j.MDC.get("boardClientIp")).isNull();
            assertThat(org.slf4j.MDC.get("correlationId")).isNotEqualTo("parent");
            if(reject) throw new GitTransitionException("TARGET_MISSING");
            return "APPLIED";
        });
        var listener=new GitActivityEventListener(service,metrics);
        UUID id=UUID.randomUUID();
        var event=new GitCommitLinkedEvent(id,"sha","repo",null,Instant.now(),id,id,id);
        org.slf4j.MDC.put("correlationId","parent");
        org.slf4j.MDC.put("userId","parent-user");
        org.slf4j.MDC.put("boardClientIp","127.0.0.1");
        var previous=org.slf4j.MDC.getCopyOfContextMap();
        try {
            if(reject) assertThatThrownBy(()->listener.on(event)).hasMessage("TARGET_MISSING");
            else assertThatCode(()->listener.on(event)).doesNotThrowAnyException();
            assertThat(org.slf4j.MDC.getCopyOfContextMap()).isEqualTo(previous);
        } finally { org.slf4j.MDC.clear(); }
    }
}
