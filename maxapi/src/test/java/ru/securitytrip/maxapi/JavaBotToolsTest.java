package ru.securitytrip.maxapi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.securitytrip.maxapi.bot.BotConfig;
import ru.securitytrip.maxapi.bot.BroadcastReport;
import ru.securitytrip.maxapi.bot.BroadcastTarget;
import ru.securitytrip.maxapi.bot.Broadcaster;
import ru.securitytrip.maxapi.bot.Confirmation;
import ru.securitytrip.maxapi.bot.InMemoryBroadcastStore;
import ru.securitytrip.maxapi.bot.InMemoryChatStore;
import ru.securitytrip.maxapi.bot.KnownChat;
import ru.securitytrip.maxapi.bot.MaxBot;
import ru.securitytrip.maxapi.bot.Paginator;
import ru.securitytrip.maxapi.bot.Scene;
import ru.securitytrip.maxapi.bot.Translations;
import ru.securitytrip.maxapi.dsl.Buttons;
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest;
import ru.securitytrip.maxapi.model.chat.ChatType;
import ru.securitytrip.maxapi.model.keyboard.Button;
import ru.securitytrip.maxapi.model.message.NewMessageBody;
import ru.securitytrip.maxapi.model.update.BotAddedToChatUpdate;
import ru.securitytrip.maxapi.model.user.User;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 1.6 bot tools as Java code sees them: the chat registry, broadcasts, scenes, keyboards and translations. */
class JavaBotToolsTest {
    private final FakeApi api = new FakeApi();
    private final List<String> log = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void chatRegistryAndBroadcast() {
        InMemoryChatStore chats = new InMemoryChatStore();
        try (MaxBot bot = new MaxBot(api.getClient(), BotConfig.builder().chatStore(chats).build())) {
            bot.processUpdateBlocking(new BotAddedToChatUpdate(1, 10, new User(7, "Ann", null, null, false, null), false));
            List<KnownChat> groups = bot.chatsBlocking(ChatType.CHAT);
            assertEquals(1, groups.size());
            assertEquals(1, bot.chatsBlocking().size());

            Broadcaster broadcaster = new Broadcaster(api.getClient(), new InMemoryBroadcastStore());
            List<BroadcastTarget> targets = groups.stream().map(BroadcastTarget::of).toList();
            BroadcastReport report = broadcaster.sendBlocking("java", targets,
                    target -> NewMessageBody.builder().text("Hi " + target.getId()).build());
            assertEquals(1, report.getSent());
            assertTrue(report.isComplete());
            assertEquals("Hi 10", api.getTexts().get(0));
        }
    }

    @Test
    void scenesKeyboardsAndTranslations() {
        Translations texts = Translations.of("ru", Map.of("ru", Map.of("name", "Как вас зовут?", "thanks", "Спасибо, {0}!")));
        Scene survey = Scene.builder("survey")
                .enterOnCommand("survey")
                .step("name", step -> step
                        .prompt(ctx -> ctx.replyBlocking(texts.text(ctx, "name")))
                        .validate("Too short", answer -> answer.length() > 1))
                .onFinish(ctx -> ctx.replyBlocking(texts.text(ctx, "thanks", ctx.getAnswers().get("name"))))
                .build();
        try (MaxBot bot = new MaxBot(api.getClient())) {
            bot.include(survey);
            bot.processUpdateBlocking(FakeApi.Companion.message("/survey", 100, 1, ChatType.DIALOG, 1));
            bot.processUpdateBlocking(FakeApi.Companion.message("A", 100, 1, ChatType.DIALOG, 2));
            bot.processUpdateBlocking(FakeApi.Companion.message("Ann", 100, 1, ChatType.DIALOG, 3));
        }
        assertEquals(Arrays.asList("Как вас зовут?", "Too short", "Как вас зовут?", "Спасибо, Ann!"), api.getTexts());

        Paginator paginator = new Paginator("items", 2);
        InlineKeyboardAttachmentRequest keyboard = paginator.keyboard(Arrays.asList("a", "b", "c"), 0, item -> Buttons.callback(item, "pick:" + item));
        assertEquals(2, keyboard.getPayload().getButtons().size() - 1);
        List<Button> yesNo = new Confirmation("delete").buttons("42", "Да", "Нет");
        assertEquals("Да", yesNo.get(0).getText());
    }

    @Test
    void staticFactories() {
        assertEquals(BroadcastTarget.user(1), BroadcastTarget.parse("u:1"));
        assertEquals(BroadcastTarget.chat(2), BroadcastTarget.chats(List.of(2L)).get(0));
        assertEquals(List.of(BroadcastTarget.user(3)), BroadcastTarget.users(List.of(3L)));
        assertEquals("Hello, Ann!", Translations.fromBundles("i18n/messages", "ru", "en").text("en", "hello", "Ann"));
    }
}
