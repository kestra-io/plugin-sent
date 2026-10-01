import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Local-only Sent v3 stub used by the Kestra runtime smoke tests. */
public final class MockSent {
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final byte[] UNAUTHORIZED = json(
        "{\"success\":false,\"error\":{\"code\":\"unauthorized\",\"message\":\"Invalid local QA key\"}}"
    );

    private MockSent() {
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 9090), 0);
        server.createContext("/v3", MockSent::dispatch);
        server.start();
        System.out.println("Local Sent QA stub listening on 127.0.0.1:9090 (all 13 actions enabled)");
    }

    private static void dispatch(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        if (!authorized(exchange)) {
            respond(exchange, 401, UNAUTHORIZED, false);
            return;
        }

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String decodedPath = exchange.getRequestURI().getPath();
        String requestText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT);

        if (method.equals("GET") && path.equals("/v3/me")) {
            success(exchange, 200, "{\"id\":\"acct_local_qa\",\"name\":\"Local Kestra QA\",\"plan\":\"mock\"}", false);
        } else if (method.equals("POST") && path.equals("/v3/contacts")) {
            requireSafeMutation(exchange, requestText, "{\"id\":\"contact_local_qa\",\"phone_number\":\"+12025550123\",\"default_channel\":\"sms\"}");
        } else if (method.equals("PATCH") && path.equals("/v3/contacts/contact_local_qa")) {
            requireSafeMutation(exchange, requestText, "{\"id\":\"contact_local_qa\",\"default_channel\":\"sent\",\"opt_out\":false}");
        } else if (method.equals("GET") && path.equals("/v3/contacts/contact_local_qa")) {
            success(exchange, 200, "{\"id\":\"contact_local_qa\",\"phone_number\":\"+12025550123\",\"default_channel\":\"sent\",\"opt_out\":false}", false);
        } else if (method.equals("GET") && path.equals("/v3/contacts")) {
            success(exchange, 200, list("contacts", "{\"id\":\"contact_local_qa\",\"phone_number\":\"+12025550123\"}"), false);
        } else if (method.equals("POST") && path.equals("/v3/messages")) {
            requireSafeMutation(
                exchange,
                requestText,
                "{\"id\":\"msg_local_qa\",\"status\":\"QUEUED\",\"recipients\":[{\"message_id\":\"msg_local_qa\"}]}"
            );
        } else if (method.equals("GET") && path.equals("/v3/messages/msg_local_qa")) {
            success(exchange, 200, "{\"id\":\"msg_local_qa\",\"status\":\"DELIVERED\"}", false);
        } else if (method.equals("GET") && path.equals("/v3/messages/msg_local_qa/activities")) {
            success(
                exchange,
                200,
                "{\"message_id\":\"msg_local_qa\",\"activities\":[{\"status\":\"QUEUED\"},{\"status\":\"DELIVERED\"}]}",
                false
            );
        } else if (
            method.equals("GET")
                && (path.equals("/v3/numbers/lookup/%2B12025550123") || decodedPath.equals("/v3/numbers/lookup/+12025550123"))
        ) {
            success(
                exchange,
                200,
                "{\"phone_number\":\"+12025550123\",\"valid\":true,\"country_code\":\"US\",\"carrier\":\"Local QA\"}",
                false
            );
        } else if (method.equals("GET") && path.equals("/v3/sender-profiles/profile_local_qa")) {
            success(exchange, 200, "{\"id\":\"profile_local_qa\",\"name\":\"Local QA Sender\",\"status\":\"active\"}", false);
        } else if (method.equals("GET") && path.equals("/v3/sender-profiles")) {
            success(exchange, 200, list("sender_profiles", "{\"id\":\"profile_local_qa\",\"name\":\"Local QA Sender\"}"), false);
        } else if (method.equals("GET") && path.equals("/v3/templates/template_local_qa")) {
            success(exchange, 200, "{\"id\":\"template_local_qa\",\"name\":\"local_test_template\",\"status\":\"approved\"}", false);
        } else if (method.equals("GET") && path.equals("/v3/templates")) {
            success(exchange, 200, list("templates", "{\"id\":\"template_local_qa\",\"name\":\"local_test_template\",\"status\":\"approved\"}"), false);
        } else if (method.equals("GET") && path.equals("/v3/qa/summary")) {
            success(exchange, 200, "{\"requests\":" + REQUESTS.get() + ",\"mode\":\"local-only\"}", false);
        } else {
            respond(
                exchange,
                404,
                json("{\"success\":false,\"error\":{\"code\":\"not_found\",\"message\":\"Unknown local QA route\"}}"),
                false
            );
        }
    }

    private static void requireSafeMutation(HttpExchange exchange, String requestText, String data) throws IOException {
        boolean safe = exchange.getRequestHeaders().getFirst("Idempotency-Key") != null && requestText.contains("\"sandbox\":true");
        if (!safe) {
            respond(
                exchange,
                422,
                json(
                    "{\"success\":false,\"error\":{\"code\":\"unsafe_local_qa_request\",\"message\":\"Sandbox and idempotency are required\"}}"
                ),
                false
            );
            return;
        }
        success(exchange, 200, data, true);
    }

    private static String list(String key, String item) {
        return "{\"" + key + "\":[" + item + "],\"pagination\":{\"page\":1,\"has_more\":false}}";
    }

    private static void success(HttpExchange exchange, int status, String data, boolean sandbox) throws IOException {
        String requestId = "req_local_" + REQUESTS.get();
        respond(
            exchange,
            status,
            json("{\"success\":true,\"data\":" + data + ",\"error\":null,\"meta\":{\"request_id\":\"" + requestId + "\",\"version\":\"v3\"}}"),
            sandbox
        );
    }

    private static boolean authorized(HttpExchange exchange) {
        return "local-qa-key".equals(exchange.getRequestHeaders().getFirst("x-api-key"));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, boolean sandbox) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("X-Request-Id", "req_local_" + REQUESTS.get());
        if (sandbox) {
            exchange.getResponseHeaders().set("X-Sandbox", "true");
            exchange.getResponseHeaders().set("Idempotent-Replayed", "false");
        }
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
