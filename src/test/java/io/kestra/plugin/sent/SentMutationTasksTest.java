package io.kestra.plugin.sent;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.sent.contacts.CreateContact;
import io.kestra.plugin.sent.contacts.UpdateContact;
import io.kestra.plugin.sent.messages.SendMessage;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
@WireMockTest
class SentMutationTasksTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void createsContactInSandboxWithIdempotency(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            post(urlPathEqualTo("/v3/contacts"))
                .withHeader("Idempotency-Key", equalTo("contact_123"))
                .withRequestBody(equalToJson("{\"phone_number\":\"+12025550123\",\"default_channel\":\"sms\",\"sandbox\":true}"))
                .willReturn(
                    aResponse().withStatus(201).withHeader("Content-Type", "application/json").withHeader("X-Sandbox", "true")
                        .withBody(success("{\"id\":\"contact-1\"}"))
                )
        );
        var output = CreateContact.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info))
            .phoneNumber(Property.ofValue("+12025550123")).defaultChannel(Property.ofValue("SMS"))
            .sandbox(Property.ofValue(true)).idempotencyKey(Property.ofValue("contact_123")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("contact-1", output.getData().get("id"));
        assertTrue(output.getSandbox());
    }

    @Test
    void updatesOnlyExplicitContactFields(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            patch(urlPathEqualTo("/v3/contacts/contact-1"))
                .withHeader("Idempotency-Key", equalTo("contact_update_1"))
                .withRequestBody(equalToJson("{\"opt_out\":true,\"sandbox\":true}"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withHeader("X-Sandbox", "true")
                        .withBody(success("{\"id\":\"contact-1\",\"opt_out\":true}"))
                )
        );
        var output = UpdateContact.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).contactId(Property.ofValue("contact-1"))
            .optOut(Property.ofValue(true)).sandbox(Property.ofValue(true)).idempotencyKey(Property.ofValue("contact_update_1")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals(true, output.getData().get("opt_out"));
    }

    @Test
    void sendsSandboxTextWithFallbackChannels(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            post(urlPathEqualTo("/v3/messages"))
                .withHeader("Idempotency-Key", equalTo("message_123"))
                .withRequestBody(equalToJson("{\"to\":[\"+12025550123\"],\"channel\":[\"sent\",\"sms\"],\"text\":\"Hello\",\"sandbox\":true}"))
                .willReturn(
                    aResponse().withStatus(202).withHeader("Content-Type", "application/json").withHeader("X-Sandbox", "true")
                        .withBody(success("{\"status\":\"QUEUED\",\"recipients\":[{\"message_id\":\"message-1\"}]}"))
                )
        );
        var output = SendMessage.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).to(Property.ofValue(List.of("+12025550123")))
            .channels(Property.ofValue(List.of("sent", "sms"))).text(Property.ofValue("Hello"))
            .sandbox(Property.ofValue(true)).idempotencyKey(Property.ofValue("message_123")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("QUEUED", output.getData().get("status"));
        assertTrue(output.getSandbox());
    }

    @Test
    void sendsTemplateByNameWithParameters(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            post(urlPathEqualTo("/v3/messages"))
                .withRequestBody(
                    equalToJson("{\"to\":[\"+12025550123\"],\"channel\":[\"sent\"],\"template\":{\"name\":\"order_update\",\"parameters\":{\"order_id\":\"TEST-1\"}},\"sandbox\":true}")
                )
                .willReturn(
                    aResponse().withStatus(202).withHeader("Content-Type", "application/json").withHeader("X-Sandbox", "true")
                        .withBody(success("{\"status\":\"QUEUED\"}"))
                )
        );
        var template = SendMessage.MessageTemplate.builder().name(Property.ofValue("order_update"))
            .parameters(Property.ofValue(Map.of("order_id", "TEST-1"))).build();
        var output = SendMessage.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).to(Property.ofValue(List.of("+12025550123")))
            .template(template).sandbox(Property.ofValue(true)).idempotencyKey(Property.ofValue("template_123")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("QUEUED", output.getData().get("status"));
    }

    @Test
    void rejectsAmbiguousMessageContentBeforeNetwork(WireMockRuntimeInfo info) {
        var template = SendMessage.MessageTemplate.builder().name(Property.ofValue("order_update")).build();
        var task = SendMessage.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).to(Property.ofValue(List.of("+12025550123")))
            .text(Property.ofValue("Hello")).template(template).idempotencyKey(Property.ofValue("message_123")).build();
        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertTrue(exception.getMessage().contains("exactly one"));
    }

    @Test
    void rejectsAmbiguousTemplateReferenceBeforeNetwork(WireMockRuntimeInfo info) {
        var template = SendMessage.MessageTemplate.builder().id(Property.ofValue("template-1")).name(Property.ofValue("order_update")).build();
        var task = SendMessage.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).to(Property.ofValue(List.of("+12025550123")))
            .template(template).idempotencyKey(Property.ofValue("message_123")).build();
        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertTrue(exception.getMessage().contains("exactly one"));
    }

    @Test
    void rejectsEmptyContactUpdateBeforeNetwork(WireMockRuntimeInfo info) {
        var task = UpdateContact.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).contactId(Property.ofValue("contact-1"))
            .idempotencyKey(Property.ofValue("contact_update_1")).build();
        assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
    }

    @Test
    void rejectsNonLoopbackPlainHttpBaseUrl() {
        var task = CreateContact.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(Property.ofValue("http://api.example.com/v3"))
            .phoneNumber(Property.ofValue("+12025550123")).idempotencyKey(Property.ofValue("contact_123")).build();
        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertTrue(exception.getMessage().contains("HTTPS"));
    }

    private static Property<String> apiKey() {
        return Property.ofValue("test-api-key");
    }

    private static Property<String> baseUrl(WireMockRuntimeInfo info) {
        return Property.ofValue(info.getHttpBaseUrl() + "/v3");
    }

    private static String success(String data) {
        return "{\"success\":true,\"data\":" + data + ",\"error\":null,\"meta\":{\"request_id\":\"req-test\",\"version\":\"v3\"}}";
    }
}
