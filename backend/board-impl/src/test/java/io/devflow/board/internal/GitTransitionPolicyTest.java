package io.devflow.board.internal;

import static org.assertj.core.api.Assertions.assertThat;
import io.devflow.board.internal.entity.StatusCategory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitTransitionPolicyTest {
    @ParameterizedTest
    @CsvSource({
        "TODO,IN_PROGRESS,true", "IN_PROGRESS,IN_PROGRESS,false", "IN_REVIEW,IN_PROGRESS,false", "DONE,IN_PROGRESS,false",
        "TODO,IN_REVIEW,true", "IN_PROGRESS,IN_REVIEW,true", "IN_REVIEW,IN_REVIEW,false", "DONE,IN_REVIEW,false",
        "TODO,DONE,true", "IN_PROGRESS,DONE,true", "IN_REVIEW,DONE,true", "DONE,DONE,false"
    })
    void onlyAdvancesProgress(StatusCategory from, StatusCategory to, boolean moves) {
        assertThat(GitTaskTransition.advances(from, to)).isEqualTo(moves);
    }
}
