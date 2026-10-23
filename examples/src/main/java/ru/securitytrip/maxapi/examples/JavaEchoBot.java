package ru.securitytrip.maxapi.examples;

import ru.securitytrip.maxapi.bot.MaxBot;
import ru.securitytrip.maxapi.dsl.Buttons;
import ru.securitytrip.maxapi.model.message.NewMessageBody;
import ru.securitytrip.maxapi.model.user.BotInfo;

/**
 * Echo bot in Java: commands, an inline keyboard, callback answers and long polling.
 *
 * <p>Run: {@code MAX_BOT_TOKEN=... ./gradlew :examples:run -PmainClass=ru.securitytrip.maxapi.examples.JavaEchoBot}
 */
public final class JavaEchoBot {
    public static void main(String[] args) {
        String token = System.getenv("MAX_BOT_TOKEN");
        if (token == null) {
            throw new IllegalStateException("Set the MAX_BOT_TOKEN environment variable");
        }

        try (MaxBot bot = new MaxBot(token)) {
            BotInfo me = bot.getClient().blocking().getMyInfo();
            System.out.println("Started as " + me.getFirstName() + " (@" + me.getUsername() + ")");

            // Commands go before the catch-all onMessage: the first matching handler wins.
            bot.onCommand("start", ctx -> ctx.replyBlocking(
                    NewMessageBody.builder()
                            .text("Pick an option:")
                            .keyboardRow(Buttons.callback("👍 Like", "like"), Buttons.callback("👎 Dislike", "dislike"))
                            .keyboardRow(Buttons.link("API docs", "https://dev.max.ru/docs-api"))
                            .build()));

            bot.onCallback("like", ctx -> ctx.answerBlocking("Thanks!"));

            bot.onCallback("dislike", ctx -> ctx.editMessageBlocking(NewMessageBody.text("Sorry to hear that 😔"), "Noted"));

            bot.onMessage(ctx -> {
                String text = ctx.getText();
                ctx.replyToMessageBlocking(text != null ? text : "I only understand text");
            });

            bot.onError((error, update) ->
                    System.err.println("Error on " + (update != null ? update.getUpdateType() : "polling") + ": " + error));

            // Blocks until bot.stop() is called or a fatal error happens.
            bot.startBlocking();
        }
    }
}
