package io.kestra.plugin.sent;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.sent.account.GetAccount;
import io.kestra.plugin.sent.contacts.GetContact;
import io.kestra.plugin.sent.contacts.ListContacts;
import io.kestra.plugin.sent.messages.GetMessageActivities;
import io.kestra.plugin.sent.messages.GetMessageStatus;
import io.kestra.plugin.sent.numbers.GetPhoneNumberDetails;
import io.kestra.plugin.sent.profiles.GetSenderProfile;
import io.kestra.plugin.sent.profiles.ListSenderProfiles;
import io.kestra.plugin.sent.templates.GetTemplate;
import io.kestra.plugin.sent.templates.ListTemplates;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@KestraTest
@WireMockTest
class SentReadTasksTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void getsAccount(WireMockRuntimeInfo info) throws Exception {
        stubFor(get(urlPathEqualTo("/v3/me")).willReturn(okJson(success("{\"id\":\"account-1\",\"plan\":\"growth\"}"))));
        var output = GetAccount.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).build().run(runContextFactory.of(Map.of()));
        assertEquals("account-1", output.getRow().get("id"));
    }

    @Test
    void getsContact(WireMockRuntimeInfo info) throws Exception {
        stubFor(get(urlPathEqualTo("/v3/contacts/contact-1")).willReturn(okJson(success("{\"id\":\"contact-1\"}"))));
        var output = GetContact.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).contactId(Property.ofValue("contact-1")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("contact-1", output.getRow().get("id"));
    }

    @Test
    void listsAndPaginatesContacts(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            get(urlPathEqualTo("/v3/contacts")).withQueryParam("page", equalTo("1"))
                .willReturn(okJson(success("{\"contacts\":[{\"id\":\"contact-1\"}],\"pagination\":{\"page\":1,\"has_more\":true}}")))
        );
        stubFor(
            get(urlPathEqualTo("/v3/contacts")).withQueryParam("page", equalTo("2"))
                .willReturn(okJson(success("{\"contacts\":[{\"id\":\"contact-2\"}],\"pagination\":{\"page\":2,\"has_more\":false}}")))
        );
        var output = ListContacts.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).search(Property.ofValue("customer"))
            .fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of(Map.of()));
        assertEquals(2L, output.getSize());
        assertEquals("contact-2", output.getRows().get(1).get("id"));
        verify(getRequestedFor(urlPathEqualTo("/v3/contacts")).withQueryParam("search", equalTo("customer")));
    }

    @Test
    void storesContactListInInternalStorage(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            get(urlPathEqualTo("/v3/contacts"))
                .willReturn(okJson(success("{\"contacts\":[{\"id\":\"contact-1\"}],\"pagination\":{\"page\":1,\"has_more\":false}}")))
        );
        var output = ListContacts.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).fetchType(Property.ofValue(FetchType.STORE)).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals(1L, output.getSize());
        assertNotNull(output.getUri());
    }

    @Test
    void getsMessageStatus(WireMockRuntimeInfo info) throws Exception {
        stubFor(get(urlPathEqualTo("/v3/messages/message-1")).willReturn(okJson(success("{\"id\":\"message-1\",\"status\":\"DELIVERED\"}"))));
        var output = GetMessageStatus.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).messageId(Property.ofValue("message-1")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("DELIVERED", output.getRow().get("status"));
    }

    @Test
    void getsMessageActivities(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            get(urlPathEqualTo("/v3/messages/message-1/activities"))
                .willReturn(okJson(success("{\"message_id\":\"message-1\",\"activities\":[{\"status\":\"SENT\"}]}")))
        );
        var output = GetMessageActivities.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).messageId(Property.ofValue("message-1")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("message-1", output.getRow().get("message_id"));
    }

    @Test
    void listsSenderProfilesWithoutApplyingProfileScope(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            get(urlPathEqualTo("/v3/sender-profiles"))
                .willReturn(okJson(success("{\"sender_profiles\":[{\"id\":\"profile-1\"}],\"pagination\":{\"page\":1,\"has_more\":false}}")))
        );
        var output = ListSenderProfiles.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).profileId(Property.ofValue("must-not-be-sent")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("profile-1", output.getRows().getFirst().get("id"));
        verify(getRequestedFor(urlPathEqualTo("/v3/sender-profiles")).withHeader("x-profile-id", absent()));
    }

    @Test
    void getsSenderProfile(WireMockRuntimeInfo info) throws Exception {
        stubFor(get(urlPathEqualTo("/v3/sender-profiles/profile-1")).willReturn(okJson(success("{\"id\":\"profile-1\"}"))));
        var output = GetSenderProfile.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).senderProfileId(Property.ofValue("profile-1")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("profile-1", output.getRow().get("id"));
    }

    @Test
    void listsFilteredTemplates(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            get(urlPathEqualTo("/v3/templates")).withQueryParam("status", equalTo("approved"))
                .willReturn(okJson(success("{\"templates\":[{\"id\":\"template-1\"}],\"pagination\":{\"page\":1,\"has_more\":false}}")))
        );
        var output = ListTemplates.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).status(Property.ofValue("approved")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("template-1", output.getRows().getFirst().get("id"));
    }

    @Test
    void getsTemplate(WireMockRuntimeInfo info) throws Exception {
        stubFor(get(urlPathEqualTo("/v3/templates/template-1")).willReturn(okJson(success("{\"id\":\"template-1\",\"name\":\"order_update\"}"))));
        var output = GetTemplate.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).templateId(Property.ofValue("template-1")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals("order_update", output.getRow().get("name"));
    }

    @Test
    void getsPhoneNumberDetails(WireMockRuntimeInfo info) throws Exception {
        stubFor(get(urlPathEqualTo("/v3/numbers/lookup/%2B12025550123")).willReturn(okJson(success("{\"valid\":true,\"country_code\":\"US\"}"))));
        var output = GetPhoneNumberDetails.builder().id(io.kestra.core.utils.IdUtils.create()).apiKey(apiKey()).baseUrl(baseUrl(info)).phoneNumber(Property.ofValue("+12025550123")).build()
            .run(runContextFactory.of(Map.of()));
        assertEquals(true, output.getRow().get("valid"));
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
