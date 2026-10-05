package io.kestra.plugin.sent;

import lombok.Getter;

@Getter
public class SentApiException extends Exception {
    private final int statusCode;
    private final String errorCode;
    private final String requestId;

    SentApiException(String message, int statusCode, String errorCode, String requestId) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    SentApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.errorCode = null;
        this.requestId = null;
    }
}
