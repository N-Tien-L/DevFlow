package io.devflow.notification.api;

import java.util.List;

public record NotificationPage(List<NotificationResponse> content, int page, int size, boolean hasNext) {
    public NotificationPage { content = List.copyOf(content); }
}
