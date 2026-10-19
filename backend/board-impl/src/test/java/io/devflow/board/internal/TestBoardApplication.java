package io.devflow.board.internal;

import io.devflow.board.internal.entity.BoardEntity;
import io.devflow.board.internal.entity.BoardAuditEntity;
import io.devflow.board.internal.entity.ColumnEntity;
import io.devflow.board.internal.entity.CommentEntity;
import io.devflow.board.internal.entity.TaskEntity;
import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.board.internal.repository.BoardAuditRepository;
import io.devflow.board.internal.repository.ColumnRepository;
import io.devflow.board.internal.repository.CommentRepository;
import io.devflow.board.internal.repository.TaskRepository;
import io.devflow.common.config.JpaAuditingConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Minimal test application that loads only Board-owned entities and repositories. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {
        BoardEntity.class, BoardAuditEntity.class, ColumnEntity.class, TaskEntity.class, CommentEntity.class
})
@EnableJpaRepositories(basePackageClasses = {
        BoardRepository.class, BoardAuditRepository.class, ColumnRepository.class, TaskRepository.class,
        CommentRepository.class
})
@Import(JpaAuditingConfig.class)
public class TestBoardApplication {
}
