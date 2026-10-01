package io.kestra.plugin.sent;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
class SentEnvelope {
    private Boolean success;
    private Map<String, Object> data;
    private Error error;
    private Meta meta;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Error {
        private String code;
        private String message;
        private Map<String, Object> details;
        @JsonProperty("doc_url")
        private String docUrl;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Meta {
        @JsonProperty("request_id")
        private String requestId;
        private String timestamp;
        private String version;
    }
}
