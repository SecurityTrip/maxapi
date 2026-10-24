package ru.securitytrip.maxapi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.securitytrip.maxapi.bot.BlockingStateStorage;
import ru.securitytrip.maxapi.bot.BotConfig;
import ru.securitytrip.maxapi.bot.CallbackData;
import ru.securitytrip.maxapi.bot.DialogState;
import ru.securitytrip.maxapi.bot.Filters;
import ru.securitytrip.maxapi.bot.MaxBot;
import ru.securitytrip.maxapi.bot.MessageContext;
import ru.securitytrip.maxapi.bot.Router;
import ru.securitytrip.maxapi.bot.StateKey;
import ru.securitytrip.maxapi.bot.UpdateDeduplicator;
import ru.securitytrip.maxapi.client.MaxJson;
import ru.securitytrip.maxapi.events.MaxEventListener;
import ru.securitytrip.maxapi.events.RequestEvent;
import ru.securitytrip.maxapi.events.UpdateEvent;
import ru.securitytrip.maxapi.model.chat.ChatType;
import ru.securitytrip.maxapi.model.update.Update;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The 0.3 bot features as Java code sees them: routers, middleware, filters, states and callback data. */
class JavaFeaturesTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final MaxBot bot = new MaxBot("token");

    @AfterEach
    void tearDown() {
        bot.close();
    }

    private static Update message(String text, String chatType) {
        return MaxJson.parseUpdate("{\"update_type\":\"message_created\",\"timestamp\":1,\"message\":{\"sender\":{\"user_id\":7},"
                + "\"recipient\":{\"chat_id\":3,\"chat_type\":\"" + chatType + "\"},\"timestamp\":1,"
                + "\"body\":{\"mid\":\"m\",\"seq\":1,\"text\":\"" + text + "\"}}}");
    }

    private static Update callback(String payload) {
        return MaxJson.parseUpdate("{\"update_type\":\"message_callback\",\"timestamp\":1,"
                + "\"callback\":{\"timestamp\":1,\"callback_id\":\"c\",\"payload\":\"" + payload + "\",\"user\":{\"user_id\":7}}}");
    }

    @Test
    void routersMiddlewareAndFilters() {
        Router admin = new Router()
                .use((ctx, next) -> {
                    log.add("admin check");
                    next.proceed();
                })
                .onCommand("ban", "Ban a user", ctx -> log.add("ban " + ctx.getCommandArgs()));
        Predicate<MessageContext> groupText = Filters.chatType(ChatType.CHAT).and(Filters.text(Pattern.compile("^\\d+$")));
        bot.use((ctx, next) -> {
                    log.add("before");
                    next.proceed();
                })
                .include(admin)
                .onMessage(groupText, ctx -> log.add("number in group"))
                .onMessage(Filters.hasText().negate().and(Filters.fromUsers(7L)), ctx -> log.add("never"))
                .onMessage(ctx -> log.add("other"));

        bot.processUpdateBlocking(message("/ban 42", "dialog"));
        bot.processUpdateBlocking(message("12", "chat"));
        bot.processUpdateBlocking(message("12", "dialog"));

        assertEquals(List.of("before", "admin check", "ban 42", "before", "number in group", "before", "other"), log);
        assertEquals("ban", bot.commands().get(0).getName());
    }

    @Test
    void dialogStateWithACustomStorage() {
        Map<StateKey, DialogState> map = new HashMap<>();
        BlockingStateStorage storage = new BlockingStateStorage() {
            @Override
            protected DialogState load(StateKey key) {
                return map.get(key);
            }

            @Override
            protected void save(StateKey key, DialogState state) {
                if (state == null) map.remove(key); else map.put(key, state);
            }
        };
        MaxBot bot = new MaxBot("token", BotConfig.builder().stateStorage(storage).build());
        bot.onCommand("form", ctx -> ctx.setStateBlocking("name"))
                .onMessage(Filters.state("name"), ctx -> {
                    ctx.setStateBlocking("done", Map.of("name", ctx.getText()));
                    log.add("saved " + ctx.getStateData());
                });
        bot.processUpdateBlocking(message("/form", "dialog"));
        bot.processUpdateBlocking(message("Ann", "dialog"));
        bot.close();

        assertEquals(List.of("saved {name=Ann}"), log);
        assertEquals(new DialogState("done", Map.of("name", "Ann")), map.get(new StateKey(3L, 7L)));
        assertEquals(new StateKey(3L, 7L), StateKey.of(message("x", "dialog")));
    }

    @Test
    void eventListenerDefaultsIgnoreEvents() {
        MaxEventListener listener = new MaxEventListener() {
        };
        listener.onRequest(new RequestEvent("GET", "/me", 200, Duration.ZERO, 0, null));
        listener.onUpdate(new UpdateEvent(message("x", "dialog"), true, Duration.ZERO, null));
        BotConfig config = BotConfig.builder().eventListener(listener).build();
        assertEquals(listener, config.getEventListener());
        assertEquals(listener, MaxEventListener.composite(List.of(listener)));
        assertEquals("message_created:m", UpdateDeduplicator.keyOf(message("x", "dialog")));
    }

    @Test
    void callbackData() {
        CallbackData page = new CallbackData("page", "n");
        bot.onCallback(page, ctx -> log.add("page " + ctx.data(page).get("n")));
        bot.processUpdateBlocking(callback(page.pack(2)));
        assertEquals(List.of("page 2"), log);
        assertEquals("page:5", page.pack(Map.of("n", 5)));
        assertThrows(IllegalArgumentException.class, () -> page.pack(1, 2));
    }
}
