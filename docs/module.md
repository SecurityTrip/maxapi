# Module maxapi

Kotlin and Java library for [Max Bot API](https://dev.max.ru/docs-api).

It has two layers that can be used separately:

- the API client, [MaxClient][ru.securitytrip.maxapi.client.MaxClient]: every method of the Bot API with typed
  models. Kotlin gets `suspend` functions; Java gets the blocking
  [MaxBlockingClient][ru.securitytrip.maxapi.java.MaxBlockingClient] and the `CompletableFuture`-based
  [MaxAsyncClient][ru.securitytrip.maxapi.java.MaxAsyncClient];
- the bot framework, [MaxBot][ru.securitytrip.maxapi.bot.MaxBot]: update handlers, long polling and a webhook handler.

```kotlin
val bot = MaxBot(System.getenv("MAX_BOT_TOKEN"))
bot.onCommand("start") {
    reply {
        text("Pick an option:")
        keyboard { row { callback("Like", "like"); callback("Dislike", "dislike") } }
    }
}
bot.onCallback("like") { answer("Thanks!") }
bot.onMessage { replyToMessage(text ?: "I only understand text") }
bot.startPolling()
```

```java
MaxBot bot = new MaxBot(System.getenv("MAX_BOT_TOKEN"));
bot.onCommand("start", ctx -> ctx.replyBlocking("Hello!"));
bot.onCallback("like", ctx -> ctx.answerBlocking("Thanks!"));
bot.startBlocking();
```

Where to start:

- building a bot: [MaxBot][ru.securitytrip.maxapi.bot.MaxBot] and the handler contexts
  [MessageContext][ru.securitytrip.maxapi.bot.MessageContext] and
  [CallbackContext][ru.securitytrip.maxapi.bot.CallbackContext];
- calling the API directly: [MaxClient][ru.securitytrip.maxapi.client.MaxClient];
- composing messages and keyboards: [MessageBuilder][ru.securitytrip.maxapi.dsl.MessageBuilder] and
  [Buttons][ru.securitytrip.maxapi.dsl.Buttons];
- reading updates: [Update][ru.securitytrip.maxapi.model.update.Update] and its subclasses;
- handling failures: [MaxApiException][ru.securitytrip.maxapi.client.MaxApiException].

# Package ru.securitytrip.maxapi.client

The API client: [MaxClient][ru.securitytrip.maxapi.client.MaxClient] with all Bot API methods, its configuration
([MaxClientConfig][ru.securitytrip.maxapi.client.MaxClientConfig], [RetryPolicy][ru.securitytrip.maxapi.client.RetryPolicy]),
exceptions, file uploads ([UploadSource][ru.securitytrip.maxapi.client.UploadSource]) and JSON settings
([MaxJson][ru.securitytrip.maxapi.client.MaxJson]).

This package does not depend on the bot framework, so the client can be used on its own,
e.g. to send notifications from a backend service.

# Package ru.securitytrip.maxapi.java

Facades of [MaxClient][ru.securitytrip.maxapi.client.MaxClient] for Java and other non-coroutine code:
[MaxBlockingClient][ru.securitytrip.maxapi.java.MaxBlockingClient] blocks until the response arrives,
[MaxAsyncClient][ru.securitytrip.maxapi.java.MaxAsyncClient] returns `CompletableFuture`s.
Both have the same methods as `MaxClient`.

# Package ru.securitytrip.maxapi.model

Data models of the Bot API, split into subpackages by area. Models are immutable data classes.
Polymorphic families are sealed classes, and types or enum values the library does not know yet are
parsed into `Unknown*` objects and `UNKNOWN` constants instead of failing.

This package itself holds what is shared by all areas:
[ApiEnum][ru.securitytrip.maxapi.model.ApiEnum], [SimpleQueryResult][ru.securitytrip.maxapi.model.SimpleQueryResult]
and [ErrorResponse][ru.securitytrip.maxapi.model.ErrorResponse].

# Package ru.securitytrip.maxapi.model.user

Users and the bot itself: [User][ru.securitytrip.maxapi.model.user.User],
[UserWithPhoto][ru.securitytrip.maxapi.model.user.UserWithPhoto], [BotInfo][ru.securitytrip.maxapi.model.user.BotInfo]
and bot menu commands ([BotCommand][ru.securitytrip.maxapi.model.user.BotCommand]).
All user representations implement [UserInfo][ru.securitytrip.maxapi.model.user.UserInfo].

# Package ru.securitytrip.maxapi.model.chat

Dialogs, group chats and channels: [Chat][ru.securitytrip.maxapi.model.chat.Chat] and the changes applied with
[ChatPatch][ru.securitytrip.maxapi.model.chat.ChatPatch], members ([ChatMember][ru.securitytrip.maxapi.model.chat.ChatMember]),
administrators and their permissions ([ChatAdmin][ru.securitytrip.maxapi.model.chat.ChatAdmin],
[ChatAdminPermission][ru.securitytrip.maxapi.model.chat.ChatAdminPermission]), pinned messages and bot activity
([SenderAction][ru.securitytrip.maxapi.model.chat.SenderAction]).

# Package ru.securitytrip.maxapi.model.message

Messages and comments. Received: [Message][ru.securitytrip.maxapi.model.message.Message] with its
[MessageBody][ru.securitytrip.maxapi.model.message.MessageBody], and channel comments
([CommentMessage][ru.securitytrip.maxapi.model.message.CommentMessage]). Outgoing:
[NewMessageBody][ru.securitytrip.maxapi.model.message.NewMessageBody] and
[NewCommentBody][ru.securitytrip.maxapi.model.message.NewCommentBody]. Button presses:
[Callback][ru.securitytrip.maxapi.model.message.Callback] and the answer to them,
[CallbackAnswer][ru.securitytrip.maxapi.model.message.CallbackAnswer].

# Package ru.securitytrip.maxapi.model.attachment

Message attachments, in two sealed families:

- [Attachment][ru.securitytrip.maxapi.model.attachment.Attachment]: attachments of received messages
  ([PhotoAttachment][ru.securitytrip.maxapi.model.attachment.PhotoAttachment],
  [FileAttachment][ru.securitytrip.maxapi.model.attachment.FileAttachment],
  [LocationAttachment][ru.securitytrip.maxapi.model.attachment.LocationAttachment] and others);
- [AttachmentRequest][ru.securitytrip.maxapi.model.attachment.AttachmentRequest]: attachments to send
  ([PhotoAttachmentRequest][ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequest],
  [FileAttachmentRequest][ru.securitytrip.maxapi.model.attachment.FileAttachmentRequest] and others).

Also file upload types ([UploadType][ru.securitytrip.maxapi.model.attachment.UploadType]) and video playback details
([VideoAttachmentDetails][ru.securitytrip.maxapi.model.attachment.VideoAttachmentDetails]).

# Package ru.securitytrip.maxapi.model.keyboard

Inline keyboards: [Keyboard][ru.securitytrip.maxapi.model.keyboard.Keyboard] and the sealed family of
[Button][ru.securitytrip.maxapi.model.keyboard.Button]s. Create buttons with
[Buttons][ru.securitytrip.maxapi.dsl.Buttons] or the keyboard DSL.

# Package ru.securitytrip.maxapi.model.markup

Text formatting: the [TextFormat][ru.securitytrip.maxapi.model.markup.TextFormat] of outgoing messages, and the
[MarkupElement][ru.securitytrip.maxapi.model.markup.MarkupElement] ranges the server reports for received ones.

# Package ru.securitytrip.maxapi.model.update

Events delivered by long polling or webhooks: the sealed [Update][ru.securitytrip.maxapi.model.update.Update] family
with one class per [UpdateType][ru.securitytrip.maxapi.model.update.UpdateType]
([MessageCreatedUpdate][ru.securitytrip.maxapi.model.update.MessageCreatedUpdate],
[MessageCallbackUpdate][ru.securitytrip.maxapi.model.update.MessageCallbackUpdate],
[BotStartedUpdate][ru.securitytrip.maxapi.model.update.BotStartedUpdate] and others), and pages of updates
([UpdateList][ru.securitytrip.maxapi.model.update.UpdateList]).

# Package ru.securitytrip.maxapi.model.subscription

Webhook subscriptions: [Subscription][ru.securitytrip.maxapi.model.subscription.Subscription] and
[SubscriptionRequestBody][ru.securitytrip.maxapi.model.subscription.SubscriptionRequestBody].

# Package ru.securitytrip.maxapi.dsl

Builders for outgoing messages and inline keyboards: the Kotlin DSL
([message][ru.securitytrip.maxapi.dsl.message], [keyboard][ru.securitytrip.maxapi.dsl.keyboard]) and fluent builders
for Java ([MessageBuilder][ru.securitytrip.maxapi.dsl.MessageBuilder], [KeyboardBuilder][ru.securitytrip.maxapi.dsl.KeyboardBuilder],
[Buttons][ru.securitytrip.maxapi.dsl.Buttons]).

# Package ru.securitytrip.maxapi.bot

The bot framework: [MaxBot][ru.securitytrip.maxapi.bot.MaxBot] routes updates to handlers, receives them by long polling
or from a webhook through [WebhookHandler][ru.securitytrip.maxapi.bot.WebhookHandler], and passes each handler a context
with shortcuts to answer ([UpdateContext][ru.securitytrip.maxapi.bot.UpdateContext] and its subclasses).
