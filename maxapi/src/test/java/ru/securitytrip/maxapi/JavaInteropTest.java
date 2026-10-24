package ru.securitytrip.maxapi;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.securitytrip.maxapi.bot.BotConfig;
import ru.securitytrip.maxapi.bot.MaxBot;
import ru.securitytrip.maxapi.bot.WebhookHandler;
import ru.securitytrip.maxapi.client.MaxApiException;
import ru.securitytrip.maxapi.client.MaxClient;
import ru.securitytrip.maxapi.client.MaxClientConfig;
import ru.securitytrip.maxapi.client.MaxException;
import ru.securitytrip.maxapi.client.MaxJson;
import ru.securitytrip.maxapi.client.NotFoundException;
import ru.securitytrip.maxapi.client.RetryPolicy;
import ru.securitytrip.maxapi.client.UploadSource;
import ru.securitytrip.maxapi.dsl.Buttons;
import ru.securitytrip.maxapi.dsl.KeyboardBuilder;
import ru.securitytrip.maxapi.model.attachment.FileAttachmentRequest;
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest;
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequest;
import ru.securitytrip.maxapi.model.chat.ChatPatch;
import ru.securitytrip.maxapi.model.chat.SenderAction;
import ru.securitytrip.maxapi.model.keyboard.OpenAppButton;
import ru.securitytrip.maxapi.model.message.CallbackAnswer;
import ru.securitytrip.maxapi.model.message.NewMessageBody;
import ru.securitytrip.maxapi.model.update.BotAddedToChatUpdate;
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate;
import ru.securitytrip.maxapi.model.update.Update;
import ru.securitytrip.maxapi.model.user.BotCommand;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the Java-facing parts of the API: overloads, static factories, builders and blocking helpers. */
class JavaInteropTest {
    private static final String MESSAGE =
            "{\"recipient\":{\"chat_id\":10},\"timestamp\":1,\"body\":{\"mid\":\"m1\",\"seq\":1,\"text\":\"hi\"}}";

    private MockWebServer server;
    private MaxClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = MaxClient.create(MaxClientConfig.builder("java-token")
                .baseUrl(server.url("/").toString())
                .retryPolicy(RetryPolicy.NONE)
                .attachmentReadyDelay(Duration.ofMillis(1))
                .build());
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    private static MockResponse ok(String body) {
        return new MockResponse.Builder().code(200).body(body).build();
    }

    private RecordedRequest take() throws InterruptedException {
        return server.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    void asyncFailuresCompleteTheFutureExceptionally() {
        server.enqueue(new MockResponse.Builder().code(404).body("{\"code\":\"not.found\",\"message\":\"No chat\"}").build());

        CompletableFuture<?> future = client.async().getChat(1L);

        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        NotFoundException cause = assertInstanceOf(NotFoundException.class, error.getCause());
        assertEquals(404, cause.getHttpStatus());
        assertEquals("No chat", cause.getErrorMessage());
    }

    @Test
    void exceptionHierarchyIsUnchecked() {
        server.enqueue(new MockResponse.Builder().code(500).body("{}").build());

        RuntimeException error = assertThrows(RuntimeException.class, () -> client.blocking().getMyInfo());
        assertInstanceOf(MaxException.class, error);
        assertInstanceOf(MaxApiException.class, error);
    }

    @Test
    void overloadsForOptionalParameters() throws Exception {
        for (int i = 0; i < 6; i++) {
            server.enqueue(ok(i < 2 ? "{\"members\":[]}" : i < 4 ? "{\"updates\":[]}" : "{\"success\":true}"));
        }

        client.blocking().getMembers(5L);
        client.blocking().getMembers(5L, List.of(1L, 2L));
        client.blocking().getUpdates();
        client.blocking().getUpdates(10);
        client.blocking().pinMessage(5L, "m1");
        client.blocking().removeMember(5L, 7L);

        assertEquals("/chats/5/members", take().getUrl().encodedPath());
        assertEquals("1,2", take().getUrl().queryParameter("user_ids"));
        assertNull(take().getUrl().queryParameter("limit"));
        assertEquals("10", take().getUrl().queryParameter("limit"));
        assertEquals("{\"message_id\":\"m1\"}", take().getBody().utf8());
        assertNull(take().getUrl().queryParameter("block"));
    }

    @Test
    void modelOverloadsAndFactories() {
        assertNull(new BotCommand("start").getDescription());
        assertEquals("New", new ChatPatch(null, "New").getTitle());
        assertEquals(new OpenAppButton("Open", "bot", null, null), Buttons.openApp("Open", "bot"));
        assertEquals("https://i", PhotoAttachmentRequest.ofUrl("https://i").getPayload().getUrl());
        assertEquals("t", FileAttachmentRequest.ofToken("t").getPayload().getToken());
        assertEquals(new CallbackAnswer(null, "n"), new CallbackAnswer(null, "n"));
        assertEquals("hi", NewMessageBody.text("hi").getText());

        InlineKeyboardAttachmentRequest keyboard = new KeyboardBuilder()
                .row(Buttons.callback("A", "a"), Buttons.link("B", "https://b"))
                .row(List.of(Buttons.clipboard("C", "c")))
                .build();
        assertEquals(2, keyboard.getPayload().getButtons().size());
        assertEquals("inline_keyboard", keyboard.getType());
    }

    @Test
    void uploadSourcesFromJava() throws Exception {
        server.enqueue(ok("{\"url\":\"" + server.url("/upload") + "\"}"));
        server.enqueue(ok("{\"token\":\"f\"}"));

        FileAttachmentRequest file = client.blocking()
                .uploadFile(UploadSource.of(new java.io.ByteArrayInputStream(new byte[] {1, 2}), "a.bin"));

        assertEquals("f", file.getPayload().getToken());
        assertEquals("a.bin", UploadSource.of(new byte[0], "a.bin").getFileName());
    }

    @Test
    void contextBlockingHelpers() throws Exception {
        for (int i = 0; i < 2; i++) {
            server.enqueue(ok("{\"message\":" + MESSAGE + "}"));
        }
        for (int i = 0; i < 5; i++) {
            server.enqueue(ok("{\"success\":true}"));
        }
        List<Throwable> errors = new ArrayList<>();
        MaxBot bot = new MaxBot(client, BotConfig.builder().build());
        bot.onError((error, update) -> errors.add(error));
        bot.onMessage(ctx -> {
            ctx.replyBlocking(NewMessageBody.text("reply"));
            ctx.replyToMessageBlocking("quoted");
            ctx.sendActionBlocking(SenderAction.TYPING_ON);
            assertSame(ctx.getClient().blocking(), ctx.blocking());
        });
        bot.onCallback(ctx -> {
            ctx.answerBlocking(new CallbackAnswer(null, "a"));
            ctx.editMessageBlocking(NewMessageBody.text("edited"));
            ctx.editMessageBlocking(NewMessageBody.text("edited"), "with note");
        });

        bot.processUpdateBlocking(MaxJson.parseUpdate(
                "{\"update_type\":\"message_created\",\"timestamp\":1,\"message\":" + MESSAGE + "}"));
        bot.processUpdateBlocking(MaxJson.parseUpdate(
                "{\"update_type\":\"message_callback\",\"timestamp\":1,"
                        + "\"callback\":{\"timestamp\":1,\"callback_id\":\"cb\",\"user\":{\"user_id\":3}},\"message\":" + MESSAGE + "}"));

        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals("{\"text\":\"reply\"}", take().getBody().utf8());
        assertEquals("{\"text\":\"quoted\",\"link\":{\"type\":\"reply\",\"mid\":\"m1\"}}", take().getBody().utf8());
        assertEquals("/chats/10/actions", take().getUrl().encodedPath());
        assertEquals("{\"notification\":\"a\"}", take().getBody().utf8());
        assertEquals("{\"message\":{\"text\":\"edited\"}}", take().getBody().utf8());
        assertEquals("{\"message\":{\"text\":\"edited\"},\"notification\":\"with note\"}", take().getBody().utf8());
    }

    @Test
    void typedUpdateHandlersAndWebhookFromJava() {
        List<String> seen = new ArrayList<>();
        MaxBot bot = new MaxBot(client);
        bot.onUpdate(BotAddedToChatUpdate.class, ctx -> seen.add("added to " + ctx.getUpdate().getChatId()));
        bot.onUpdate(Update.class, ctx -> seen.add("other " + ctx.getUpdate().getUpdateType()));
        WebhookHandler handler = bot.webhookHandler("secret");

        assertFalse(handler.handleBlocking("{}", "wrong"));
        assertTrue(handler.handleBlocking(
                "{\"update_type\":\"bot_added\",\"timestamp\":1,\"chat_id\":-3,\"user\":{\"user_id\":1}}", "secret"));
        assertTrue(handler.handleBlocking(
                "{\"update_type\":\"dialog_cleared\",\"timestamp\":1,\"chat_id\":1,\"user\":{\"user_id\":1}}".getBytes(),
                "secret"));
        assertEquals("X-Max-Bot-Api-Secret", WebhookHandler.SECRET_HEADER);
        assertThrows(MaxException.class, () -> handler.handleBlocking("not json", "secret"));

        assertEquals(List.of("added to -3", "other dialog_cleared"), seen);
        assertNull(bot.subscribedUpdateTypes());
    }

    @Test
    void parsedUpdatesAreTypedForInstanceof() {
        Update update = MaxJson.parseUpdate(
                "{\"update_type\":\"message_created\",\"timestamp\":5,\"message\":" + MESSAGE + "}");

        assertInstanceOf(MessageCreatedUpdate.class, update);
        MessageCreatedUpdate created = (MessageCreatedUpdate) update;
        assertEquals("hi", created.getMessage().getBody().getText());
        assertEquals(10L, created.getChatId());
        assertEquals(5L, created.getTimestamp());
    }
}
