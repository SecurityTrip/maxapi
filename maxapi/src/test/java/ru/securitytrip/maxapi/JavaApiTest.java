package ru.securitytrip.maxapi;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.securitytrip.maxapi.bot.BotConfig;
import ru.securitytrip.maxapi.bot.MaxBot;
import ru.securitytrip.maxapi.client.MaxClient;
import ru.securitytrip.maxapi.client.MaxClientConfig;
import ru.securitytrip.maxapi.client.MaxJson;
import ru.securitytrip.maxapi.client.RetryPolicy;
import ru.securitytrip.maxapi.client.UnauthorizedException;
import ru.securitytrip.maxapi.dsl.Buttons;
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest;
import ru.securitytrip.maxapi.model.chat.Chat;
import ru.securitytrip.maxapi.model.keyboard.CallbackButton;
import ru.securitytrip.maxapi.model.message.Message;
import ru.securitytrip.maxapi.model.message.NewMessageBody;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks that the API is convenient to call from Java, without Kotlin-specific constructs. */
class JavaApiTest {
    private MockWebServer server;
    private MaxClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = MaxClient.create(MaxClientConfig.builder("java-token")
                .baseUrl(server.url("/").toString())
                .retryPolicy(RetryPolicy.NONE)
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

    @Test
    void blockingClientWithBuilders() throws Exception {
        server.enqueue(ok("{\"message\":{\"recipient\":{\"chat_id\":5},\"timestamp\":1,\"body\":{\"mid\":\"m1\",\"seq\":1,\"text\":\"Hi\"}}}"));

        NewMessageBody body = NewMessageBody.builder()
                .text("Hi")
                .keyboardRow(Buttons.callback("Yes", "yes"), Buttons.requestGeoLocation("Where?"))
                .build();
        Message sent = client.blocking().sendMessageToChat(5L, body);

        assertEquals("m1", sent.getBody().getMid());
        InlineKeyboardAttachmentRequest keyboard = (InlineKeyboardAttachmentRequest) body.getAttachments().get(0);
        assertEquals(new CallbackButton("Yes", "yes"), keyboard.getPayload().getButtons().get(0).get(0));
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("java-token", request.getHeaders().get("Authorization"));
        assertEquals("5", request.getUrl().queryParameter("chat_id"));
    }

    @Test
    void asyncClientReturnsFutures() throws Exception {
        server.enqueue(ok("{\"chat_id\":9,\"type\":\"channel\",\"status\":\"active\"}"));

        CompletableFuture<Chat> future = client.async().getChat(9L);

        assertEquals(9L, future.get(5, TimeUnit.SECONDS).getChatId());
    }

    @Test
    void errorsAreUncheckedExceptions() {
        server.enqueue(new MockResponse.Builder().code(401).body("{\"code\":\"verify.token\",\"message\":\"Invalid\"}").build());

        UnauthorizedException error = assertThrows(UnauthorizedException.class, () -> client.blocking().getMyInfo());
        assertEquals("verify.token", error.getErrorCode());
    }

    @Test
    void botHandlersFromJava() {
        server.enqueue(ok("{\"message\":{\"recipient\":{\"chat_id\":1},\"timestamp\":1,\"body\":{\"mid\":\"m2\",\"seq\":1}}}"));
        List<String> seen = new ArrayList<>();
        MaxBot bot = new MaxBot(client, BotConfig.builder().parallelism(2).build());
        bot.onCommand("start", ctx -> {
            seen.add("start " + ctx.getCommandArgs());
            ctx.replyBlocking("Hello from Java");
        });
        bot.onMessage(ctx -> seen.add("text " + ctx.getText()));
        bot.onError((error, update) -> seen.add("error " + error.getMessage()));

        bot.processUpdateBlocking(MaxJson.parseUpdate(
                "{\"update_type\":\"message_created\",\"timestamp\":1,"
                        + "\"message\":{\"recipient\":{\"chat_id\":1},\"timestamp\":1,\"body\":{\"mid\":\"m\",\"seq\":1,\"text\":\"/start now\"}}}"));
        bot.processUpdateBlocking(MaxJson.parseUpdate(
                "{\"update_type\":\"message_created\",\"timestamp\":1,"
                        + "\"message\":{\"recipient\":{\"chat_id\":1},\"timestamp\":1,\"body\":{\"mid\":\"m\",\"seq\":1,\"text\":\"plain\"}}}"));

        assertEquals(List.of("start now", "text plain"), seen);
        assertInstanceOf(MaxBot.class, bot.onCallback("x", ctx -> ctx.answerBlocking("ok")));
    }
}
