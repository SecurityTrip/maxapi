package ru.securitytrip.maxapi.test;

import org.junit.jupiter.api.Test;
import ru.securitytrip.maxapi.model.chat.ChatType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BotTester as a Java test sees it. */
class JavaBotTesterTest {
    @Test
    void javaHandlers() {
        try (BotTester tester = new BotTester()) {
            tester.getBot()
                    .onCommand("start", ctx -> ctx.replyBlocking("Hello!"))
                    .onCallback("like", ctx -> ctx.answerBlocking("Thanks!"))
                    .onBotStarted(ctx -> ctx.replyBlocking("ref " + ctx.getPayload()));

            tester.sendMessageBlocking("/start");
            tester.sendMessageBlocking("/start", 5L);
            tester.sendMessageBlocking("/start", 5L, 6L);
            tester.sendMessageBlocking("/start", 5L, 6L, ChatType.CHAT);
            tester.pressButtonBlocking("like");
            tester.pressButtonBlocking("like", 5L);
            tester.startBotBlocking("promo");
            tester.startBotBlocking("promo", 9L);
            tester.sendBlocking(TestUpdates.callback("like"));
            tester.stub("GET", "/me", "{\"user_id\":1}");

            assertEquals(List.of(100L, 5L, 5L, 5L, 100L, 100L), tester.getSentMessages().stream().map(SentMessage::getChatId).toList());
            assertEquals(3, tester.getCallbackAnswers().size());
            assertEquals(1L, TestUpdates.message("x").getUser().getUserId());
            assertEquals("ref", TestUpdates.botStarted("ref").getPayload());
            assertEquals(7L, TestUpdates.user(7L).getUserId());
        }
    }
}
