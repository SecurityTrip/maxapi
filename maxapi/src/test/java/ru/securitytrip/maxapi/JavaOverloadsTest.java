package ru.securitytrip.maxapi;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.securitytrip.maxapi.bot.BotConfig;
import ru.securitytrip.maxapi.bot.MaxBot;
import ru.securitytrip.maxapi.client.MaxClient;
import ru.securitytrip.maxapi.client.MaxClientConfig;
import ru.securitytrip.maxapi.client.RetryPolicy;
import ru.securitytrip.maxapi.client.UploadSource;
import ru.securitytrip.maxapi.dsl.Buttons;
import ru.securitytrip.maxapi.java.MaxAsyncClient;
import ru.securitytrip.maxapi.java.MaxBlockingClient;
import ru.securitytrip.maxapi.model.attachment.ContactAttachmentRequestPayload;
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequestPayload;
import ru.securitytrip.maxapi.model.attachment.ShareAttachmentPayload;
import ru.securitytrip.maxapi.model.chat.ChatAdmin;
import ru.securitytrip.maxapi.model.chat.ChatAdminPermission;
import ru.securitytrip.maxapi.model.chat.ChatPatch;
import ru.securitytrip.maxapi.model.message.CallbackAnswer;
import ru.securitytrip.maxapi.model.message.NewCommentBody;
import ru.securitytrip.maxapi.model.message.NewMessageBody;
import ru.securitytrip.maxapi.model.markup.TextFormat;
import ru.securitytrip.maxapi.model.subscription.SubscriptionRequestBody;
import ru.securitytrip.maxapi.model.user.BotCommand;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls every overload that {@code @JvmOverloads} generates for Java, to make sure each one
 * passes its arguments to the right parameters.
 */
class JavaOverloadsTest {
    private static final String MESSAGE =
            "{\"recipient\":{\"chat_id\":1},\"timestamp\":1,\"body\":{\"mid\":\"m\",\"seq\":1}}";

    private MockWebServer server;
    private MaxClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                String path = request.getUrl().encodedPath();
                String body;
                if (path.endsWith("/members")) {
                    body = request.getMethod().equals("GET") ? "{\"members\":[]}" : "{\"success\":true}";
                } else if (path.equals("/updates")) {
                    body = "{\"updates\":[]}";
                } else if (path.equals("/messages") && request.getMethod().equals("GET")) {
                    body = "{\"messages\":[]}";
                } else if (path.equals("/messages") && request.getMethod().equals("POST")) {
                    body = "{\"message\":" + MESSAGE + "}";
                } else if (path.endsWith("/comments") && request.getMethod().equals("GET")) {
                    body = "{\"messages\":[]}";
                } else if (path.endsWith("/comments") && request.getMethod().equals("POST")) {
                    body = "{\"message\":" + MESSAGE + "}";
                } else {
                    body = "{\"success\":true}";
                }
                return new MockResponse.Builder().code(200).body(body).build();
            }
        });
        server.start();
        client = MaxClient.create(MaxClientConfig.builder("token")
                .baseUrl(server.url("/").toString())
                .retryPolicy(RetryPolicy.NONE)
                .build());
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    private RecordedRequest take() throws InterruptedException {
        return server.takeRequest(5, TimeUnit.SECONDS);
    }

    private String query(String name) throws InterruptedException {
        return take().getUrl().queryParameter(name);
    }

    @Test
    void blockingOverloads() throws Exception {
        MaxBlockingClient api = client.blocking();
        NewMessageBody body = NewMessageBody.text("x");

        api.pinMessage(1L, "m");
        assertEquals("{\"message_id\":\"m\"}", take().getBody().utf8());

        api.getMembers(1L);
        api.getMembers(1L, List.of(2L));
        api.getMembers(1L, null, 3L);
        assertNull(query("user_ids"));
        assertEquals("2", query("user_ids"));
        assertEquals("3", query("marker"));

        api.removeMember(1L, 2L);
        assertNull(take().getUrl().queryParameter("block"));

        api.subscribe("https://hook");
        api.subscribe("https://hook", "secret");
        assertEquals("{\"url\":\"https://hook\"}", take().getBody().utf8());
        assertEquals("{\"url\":\"https://hook\",\"secret\":\"secret\"}", take().getBody().utf8());

        api.getUpdates();
        api.getUpdates(5);
        api.getUpdates(5, 1);
        api.getUpdates(5, 1, 9L);
        assertNull(query("limit"));
        assertEquals("5", query("limit"));
        assertEquals("1", query("timeout"));
        assertEquals("9", query("marker"));

        api.getMessages();
        api.getMessages(1L);
        api.getMessages(1L, List.of("a"));
        api.getMessages(1L, null, 20L);
        api.getMessages(1L, null, null, 10L);
        assertNull(query("chat_id"));
        assertEquals("1", query("chat_id"));
        assertEquals("a", query("message_ids"));
        assertEquals("20", query("before"));
        assertEquals("10", query("after"));

        assertThrows(IllegalArgumentException.class, () -> api.sendMessage(body));
        api.sendMessage(body, 7L);
        api.sendMessage(body, null, 1L);
        assertEquals("7", query("user_id"));
        assertEquals("1", query("chat_id"));

        api.answerOnCallback("cb", new CallbackAnswer());
        assertEquals("{}", take().getBody().utf8());

        api.getComments("m");
        api.getComments("m", List.of("c"));
        api.getComments("m", null, 9L);
        api.getComments("m", null, null, 1L);
        assertNull(query("comment_ids"));
        assertEquals("c", query("comment_ids"));
        assertEquals("9", query("before"));
        assertEquals("1", query("after"));

        api.sendComment("m", new NewCommentBody("c"));
        assertNull(query("disable_link_preview"));
    }

    @Test
    void asyncOverloads() throws Exception {
        MaxAsyncClient api = client.async();
        NewMessageBody body = NewMessageBody.text("x");
        List<CompletableFuture<?>> calls = List.of(
                api.pinMessage(1L, "m"),
                api.getMembers(1L), api.getMembers(1L, List.of(2L)), api.getMembers(1L, null, 3L),
                api.removeMember(1L, 2L),
                api.subscribe("https://hook"), api.subscribe("https://hook", "secret"),
                api.getUpdates(), api.getUpdates(5), api.getUpdates(5, 1), api.getUpdates(5, 1, 9L),
                api.getMessages(), api.getMessages(1L), api.getMessages(1L, List.of("a")),
                api.getMessages(1L, null, 20L), api.getMessages(1L, null, null, 10L),
                api.sendMessage(body, 7L), api.sendMessage(body, null, 1L),
                api.answerOnCallback("cb", new CallbackAnswer()),
                api.getComments("m"), api.getComments("m", List.of("c")),
                api.getComments("m", null, 9L), api.getComments("m", null, null, 1L),
                api.sendComment("m", new NewCommentBody("c")));

        for (CompletableFuture<?> call : calls) {
            call.get(5, TimeUnit.SECONDS);
        }
        assertEquals(calls.size(), server.getRequestCount());

        CompletableFuture<?> invalid = api.sendMessage(body);
        java.util.concurrent.ExecutionException error =
                assertThrows(java.util.concurrent.ExecutionException.class, () -> invalid.get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void modelConstructorOverloads() {
        assertEquals(new NewMessageBody(), new NewMessageBody(null, null, null, null, null));
        assertEquals("t", new NewMessageBody("t").getText());
        assertEquals(List.of(), new NewMessageBody("t", List.of()).getAttachments());
        assertNull(new NewMessageBody("t", null, null).getLink());
        assertEquals(false, new NewMessageBody("t", null, null, false).getNotify());

        assertNull(new NewCommentBody().getText());
        assertNull(new NewCommentBody("c", null).getLink());
        assertEquals(TextFormat.HTML, new NewCommentBody("c", null, TextFormat.HTML).getFormat());

        assertNull(new CallbackAnswer().getMessage());
        assertNull(new CallbackAnswer(NewMessageBody.text("x")).getNotification());

        assertNull(new ChatPatch().getTitle());
        assertNull(new ChatPatch(null).getIcon());
        assertEquals("d", new ChatPatch(null, "t", "d").getDescription());
        assertEquals("m", new ChatPatch(null, "t", "d", "m").getPin());
        assertEquals(true, new ChatPatch(null, "t", "d", "m", true).getNotify());

        assertNull(new ChatAdmin(1L, Set.of(ChatAdminPermission.WRITE)).getAlias());
        assertNull(new BotCommand("start").getDescription());

        assertNull(new PhotoAttachmentRequestPayload().getUrl());
        assertEquals("u", new PhotoAttachmentRequestPayload("u").getUrl());
        assertEquals("t", new PhotoAttachmentRequestPayload(null, "t").getToken());

        assertNull(new ContactAttachmentRequestPayload().getName());
        assertEquals("n", new ContactAttachmentRequestPayload("n").getName());
        assertEquals(1L, new ContactAttachmentRequestPayload("n", 1L).getContactId());
        assertEquals("v", new ContactAttachmentRequestPayload("n", 1L, "v").getVcfInfo());

        assertNull(new ShareAttachmentPayload().getUrl());
        assertEquals("u", new ShareAttachmentPayload("u").getUrl());

        assertNull(new SubscriptionRequestBody("https://hook").getSecret());
        assertEquals("s", new SubscriptionRequestBody("https://hook", "s").getSecret());

        assertEquals(3, new RetryPolicy().getMaxRetries());
        assertEquals(Duration.ofSeconds(1), new RetryPolicy(1, Duration.ofSeconds(1)).getInitialDelay());
        assertEquals(Duration.ofSeconds(9), new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(9)).getMaxDelay());
    }

    @Test
    void staticFactoriesFromJava() throws Exception {
        assertEquals("a", ru.securitytrip.maxapi.model.attachment.AudioAttachmentRequest.ofToken("a").getPayload().getToken());
        assertEquals("p", ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequest.ofToken("p").getPayload().getToken());
        assertEquals("v", ru.securitytrip.maxapi.model.attachment.VideoAttachmentRequest.ofToken("v").getPayload().getToken());
        assertEquals("s", ru.securitytrip.maxapi.model.attachment.StickerAttachmentRequest.ofCode("s").getPayload().getCode());
        assertEquals("https://x", ru.securitytrip.maxapi.model.attachment.ShareAttachmentRequest.ofUrl("https://x").getPayload().getUrl());

        java.nio.file.Path path = java.nio.file.Files.createTempFile("source", ".png");
        try {
            assertEquals(path.getFileName().toString(), UploadSource.of(path).getFileName());
            assertEquals(path.getFileName().toString(), UploadSource.of(path.toFile()).getFileName());
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }

        try (MaxClient created = MaxClient.create("token")) {
            assertEquals(MaxClientConfig.DEFAULT_BASE_URL, created.getConfig().getBaseUrl());
        }
    }

    @Test
    void factoryAndBotOverloads() {
        assertNull(Buttons.requestGeoLocation("Where").getQuick());
        assertEquals("p", Buttons.openApp("Open", "bot", "p").getPayload());
        assertEquals("a.bin", UploadSource.of(new ByteArrayInputStream(new byte[0]), "a.bin").getFileName());

        try (MaxBot owning = new MaxBot("token"); MaxBot configured = new MaxBot("token", BotConfig.builder().build())) {
            assertEquals(4, owning.getConfig().getParallelism());
            assertEquals(4, configured.getConfig().getParallelism());
        }

        MaxBot bot = new MaxBot(client);
        bot.onMessage(ctx -> { });
        assertTrue(bot.webhookHandler().verifySecret(null));
        assertTrue(bot.setWebhookBlocking("https://hook").getSuccess());
    }
}
