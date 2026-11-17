package ru.securitytrip.maxapi;

import org.junit.jupiter.api.Test;
import ru.securitytrip.maxapi.client.MaxLimits;
import ru.securitytrip.maxapi.webapp.MaxLinks;
import ru.securitytrip.maxapi.webapp.WebAppData;
import ru.securitytrip.maxapi.webapp.WebAppDataException;
import ru.securitytrip.maxapi.webapp.WebAppPhone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mini app API called from Java: static methods, overloads and unchecked exceptions. */
class JavaWebAppTest {
    private static final String TOKEN = "java-bot-token";

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String initData(long authDate) throws Exception {
        String launchParams = "auth_date=" + authDate + "\nuser={\"id\":7,\"first_name\":\"Ivan\"}";
        String hash = HexFormat.of().formatHex(hmac(hmac("WebAppData".getBytes(StandardCharsets.UTF_8), TOKEN), launchParams));
        return "auth_date=" + authDate + "&user=%7B%22id%22%3A7%2C%22first_name%22%3A%22Ivan%22%7D&hash=" + hash;
    }

    @Test
    void validateFromJava() throws Exception {
        long now = Instant.now().getEpochSecond();
        assertEquals(7, WebAppData.validate(initData(now), TOKEN).getUser().getUserId());
        assertEquals("Ivan", WebAppData.validate(initData(now), TOKEN, Duration.ofMinutes(5)).getUser().getFirstName());
        assertEquals(7, WebAppData.validate(initData(1000), TOKEN, Duration.ofHours(1), Instant.ofEpochSecond(1060)).getUser().getUserId());
        assertTrue(WebAppData.verifySignature(initData(1000), TOKEN));

        WebAppDataException e = assertThrows(WebAppDataException.class, () -> WebAppData.validate(initData(1000), TOKEN));
        assertEquals(WebAppDataException.Reason.EXPIRED, e.getReason());
    }

    @Test
    void phoneFromJava() throws Exception {
        String hash = HexFormat.of().formatHex(hmac(TOKEN.getBytes(StandardCharsets.UTF_8), "authDate=1000\nphone=79990001122\nuserId=7"));
        // An authDate of 1000 is decades old: by default it is too old.
        assertFalse(WebAppPhone.verify("+79990001122", "1000", 7, hash, TOKEN));
        assertFalse(new WebAppPhone("79990001122", "1000", hash).verify(7, TOKEN));
        assertTrue(WebAppPhone.verify("+79990001122", "1000", 7, hash, TOKEN, null));
        assertTrue(WebAppPhone.verify("+79990001122", "1000", 7, hash, TOKEN, Duration.ofMinutes(5), Instant.ofEpochSecond(1060)));
        assertTrue(new WebAppPhone("79990001122", "1000", hash).verify(7, TOKEN, null));
        assertTrue(new WebAppPhone("79990001122", "1000", hash).verify(7, TOKEN, Duration.ofMinutes(5), Instant.ofEpochSecond(1060)));
        assertFalse(WebAppPhone.verify("+79990001122", "1000", 8, hash, TOKEN, null));
    }

    @Test
    void secretsAndContactsFromJava() throws Exception {
        assertEquals(64, ru.securitytrip.maxapi.bot.WebhookHandler.secretFor(TOKEN).length());
        ru.securitytrip.maxapi.model.message.Message message = new ru.securitytrip.maxapi.model.message.Message(
                null, new ru.securitytrip.maxapi.model.message.Recipient(), 1L, null, null, null, null);
        assertEquals(null, ru.securitytrip.maxapi.bot.VerifiedContact.of(message, TOKEN));
    }

    @Test
    void linksFromJava() {
        assertEquals("https://max.ru/b?startapp", MaxLinks.miniApp("b"));
        assertEquals("https://max.ru/b?startapp=x", MaxLinks.miniApp("b", "x"));
        assertEquals("https://max.ru/b?start=x", MaxLinks.botStart("b", "x"));
        assertTrue(MaxLimits.isValidOpenAppPayload("ok"));
        assertThrows(IllegalArgumentException.class, () -> MaxLimits.checkOpenAppPayload("not ok"));
    }
}
