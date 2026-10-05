package io.kestra.plugin.sent;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

import reactor.core.publisher.Flux;

public final class SentFetchSupport {
    private SentFetchSupport() {
    }

    static SentFetchOutput single(RunContext runContext, FetchType fetchType, Map<String, Object> row) throws Exception {
        if (row == null) {
            return SentFetchOutput.builder().size(0L).build();
        }
        return switch (fetchType) {
            case FETCH_ONE -> SentFetchOutput.builder().row(row).size(1L).build();
            case FETCH -> SentFetchOutput.builder().rows(List.of(row)).size(1L).build();
            case STORE -> store(runContext, List.of(row), 1L);
            case NONE -> SentFetchOutput.builder().size(1L).build();
        };
    }

    static SentFetchOutput list(
        RunContext runContext,
        FetchType fetchType,
        int startPage,
        int pageSize,
        int maxPages,
        String itemKey,
        PageLoader loader) throws Exception {
        var rows = fetchType == FetchType.FETCH ? new ArrayList<Map<String, Object>>() : null;
        var tempFile = fetchType == FetchType.STORE ? runContext.workingDir().createTempFile(".ion").toFile() : null;
        var total = 0L;

        try (var stream = tempFile == null ? null : new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            var page = startPage;
            for (var pagesRead = 0; pagesRead < maxPages; pagesRead++, page++) {
                var response = loader.load(page, pageSize);
                var items = items(response.data(), itemKey);

                if (fetchType == FetchType.FETCH_ONE && !items.isEmpty()) {
                    return SentFetchOutput.builder().row(items.getFirst()).size(1L).build();
                }
                if (fetchType == FetchType.FETCH) {
                    rows.addAll(items);
                } else if (fetchType == FetchType.STORE && !items.isEmpty()) {
                    FileSerde.writeAll(stream, Flux.fromIterable(items)).block();
                }
                total += items.size();

                var hasMore = hasMore(response.data(), page, items);
                if (!hasMore) {
                    if (fetchType == FetchType.STORE) {
                        stream.flush();
                    }
                    break;
                }
                if (items.isEmpty()) {
                    throw new SentApiException("Sent returned malformed pagination: `has_more` was true for an empty page.", response.statusCode(), null, response.requestId());
                }
                if (pagesRead + 1 == maxPages) {
                    throw new IllegalStateException("Sent pagination exceeded maxPages=" + maxPages + ".");
                }
            }
        }

        return switch (fetchType) {
            case FETCH_ONE -> SentFetchOutput.builder().size(0L).build();
            case FETCH -> SentFetchOutput.builder().rows(rows).size(total).build();
            case STORE -> SentFetchOutput.builder().uri(runContext.storage().putFile(tempFile)).size(total).build();
            case NONE -> SentFetchOutput.builder().size(total).build();
        };
    }

    private static SentFetchOutput store(RunContext runContext, List<Map<String, Object>> rows, long size) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (var stream = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            FileSerde.writeAll(stream, Flux.fromIterable(rows)).block();
        }
        return SentFetchOutput.builder().uri(runContext.storage().putFile(tempFile)).size(size).build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data, String itemKey) {
        var value = data.get(itemKey);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Sent response data did not contain a `" + itemKey + "` list.");
        }
        for (var item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalStateException("Sent returned a non-object item in `" + itemKey + "`.");
            }
        }
        return (List<Map<String, Object>>) (List<?>) list;
    }

    private static boolean hasMore(Map<String, Object> data, int expectedPage, List<Map<String, Object>> items) {
        if (!(data.get("pagination") instanceof Map<?, ?> pagination)) {
            throw new IllegalStateException("Sent response data did not contain pagination metadata.");
        }
        var page = pagination.get("page");
        var hasMore = pagination.get("has_more");
        if (!(page instanceof Number number) || number.intValue() != expectedPage) {
            throw new IllegalStateException("Sent returned malformed pagination: unexpected page number.");
        }
        if (!(hasMore instanceof Boolean value)) {
            throw new IllegalStateException("Sent returned malformed pagination: `has_more` is missing or not boolean.");
        }
        return value;
    }

    @FunctionalInterface
    public interface PageLoader {
        SentResponse load(int page, int pageSize) throws Exception;
    }
}
