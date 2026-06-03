package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.UserAgentDetails;
import com.scalekit.urlshortener.service.impl.UserAgentParserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserAgentParserServiceTest {

    private UserAgentParserService userAgentParserService;

    @BeforeEach
    void setUp() {
        userAgentParserService = new UserAgentParserServiceImpl();
    }

    @Test
    void parse_chromeWindows_detectsCorrectly() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        UserAgentDetails details = userAgentParserService.parse(ua);
        assertEquals("Windows", details.getOs());
        assertEquals("Chrome", details.getBrowser());
        assertEquals("Desktop", details.getDeviceType());
    }

    @Test
    void parse_iphoneSafari_detectsMobile() {
        String ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1";
        UserAgentDetails details = userAgentParserService.parse(ua);
        assertEquals("iOS", details.getOs());
        assertEquals("Safari", details.getBrowser());
        assertEquals("Mobile", details.getDeviceType());
    }

    @Test
    void parse_ipad_detectsTablet() {
        String ua = "Mozilla/5.0 (iPad; CPU OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1";
        UserAgentDetails details = userAgentParserService.parse(ua);
        assertEquals("iOS", details.getOs());
        assertEquals("Safari", details.getBrowser());
        assertEquals("Tablet", details.getDeviceType());
    }

    @Test
    void parse_googlebot_detectsBot() {
        String ua = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
        UserAgentDetails details = userAgentParserService.parse(ua);
        assertEquals("Unknown", details.getOs());
        assertEquals("Bot", details.getBrowser());
        assertEquals("Bot", details.getDeviceType());
    }

    @Test
    void parse_curl_detectsBot() {
        String ua = "curl/8.4.0";
        UserAgentDetails details = userAgentParserService.parse(ua);
        assertEquals("Unknown", details.getOs());
        assertEquals("Bot", details.getBrowser());
        assertEquals("Bot", details.getDeviceType());
    }

    @Test
    void parse_firefox_linux_correctParsing() {
        String ua = "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0";
        UserAgentDetails details = userAgentParserService.parse(ua);
        assertEquals("Linux", details.getOs());
        assertEquals("Firefox", details.getBrowser());
        assertEquals("Desktop", details.getDeviceType());
    }

    @Test
    void parse_unknownAgent_returnsUnknown() {
        String ua = "Some strange user agent";
        UserAgentDetails details = userAgentParserService.parse(ua);
        assertEquals("Unknown", details.getOs());
        assertEquals("Unknown", details.getBrowser());
        assertEquals("Desktop", details.getDeviceType());
    }

    @Test
    void parse_null_doesNotThrow() {
        UserAgentDetails details = userAgentParserService.parse(null);
        assertNotNull(details);
        assertEquals("Unknown", details.getOs());
        assertEquals("Unknown", details.getBrowser());
        assertEquals("Desktop", details.getDeviceType());
    }
}
