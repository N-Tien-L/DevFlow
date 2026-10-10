package io.devflow.board.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.board.internal.entity.BoardAuditEntity;
import io.devflow.board.internal.repository.BoardAuditRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Stores audit metadata in the same transaction as the Board mutation. */
@Service
public class BoardAuditService {

    private final BoardAuditRepository repository;
    private final ObjectMapper objectMapper;

    public BoardAuditService(BoardAuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            UUID workspaceId,
            List<String> changedFields) {
        try {
            String fields = objectMapper.writeValueAsString(changedFields.stream().distinct().sorted().toList());
            repository.saveAndFlush(new BoardAuditEntity(
                    actorId,
                    action,
                    resourceType,
                    resourceId,
                    workspaceId,
                    BoardRequestContext.correlationId(),
                    BoardRequestContext.clientIp(),
                    fields));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Board audit metadata", exception);
        }
    }

    public void recordTaskMove(
            UUID actorId,
            UUID taskId,
            UUID workspaceId,
            UUID sourceColumnId,
            UUID destinationColumnId) {
        try {
            String fields = objectMapper.writeValueAsString(List.of("columnId", "position"));
            repository.saveAndFlush(new BoardAuditEntity(
                    actorId,
                    "MOVE",
                    "TASK",
                    taskId,
                    workspaceId,
                    BoardRequestContext.correlationId(),
                    BoardRequestContext.clientIp(),
                    fields,
                    sourceColumnId,
                    destinationColumnId));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Board audit metadata", exception);
        }
    }
}
