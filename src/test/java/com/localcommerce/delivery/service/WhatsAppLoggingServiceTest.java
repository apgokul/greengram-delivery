package com.localcommerce.delivery.service;

import com.localcommerce.delivery.dto.WhatsAppWebhookPayload;
import com.localcommerce.delivery.enums.MessageDirection;
import com.localcommerce.delivery.model.WhatsAppMessageLog;
import com.localcommerce.delivery.repository.WhatsAppMessageLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppLoggingServiceTest {

    @Mock
    private WhatsAppMessageLogRepository repository;

    private WhatsAppLoggingService loggingService;

    @BeforeEach
    void setUp() {
        loggingService = new WhatsAppLoggingService(repository);
    }

    @Test
    void testLogInbound_SavesWhenExternalIdIsNew() {
        when(repository.existsByExternalMessageId("wamid.new123")).thenReturn(false);

        LocalDateTime now = LocalDateTime.now();
        loggingService.logInbound("919876543210", "Hello", now, "wamid.new123");

        ArgumentCaptor<WhatsAppMessageLog> captor = ArgumentCaptor.forClass(WhatsAppMessageLog.class);
        verify(repository).save(captor.capture());

        WhatsAppMessageLog saved = captor.getValue();
        assertEquals("919876543210", saved.getPhoneNumber());
        assertEquals("Hello", saved.getMessageBody());
        assertEquals(MessageDirection.INBOUND, saved.getDirection());
        assertEquals("wamid.new123", saved.getExternalMessageId());
        assertEquals(now, saved.getTimestamp());
    }

    @Test
    void testLogInbound_SkipsWhenExternalIdAlreadyExists() {
        when(repository.existsByExternalMessageId("wamid.duplicate123")).thenReturn(true);

        loggingService.logInbound("919876543210", "Hello again", LocalDateTime.now(), "wamid.duplicate123");

        verify(repository, never()).save(any());
    }

    @Test
    void testLogInbound_SavesWhenExternalIdIsNull() {
        LocalDateTime now = LocalDateTime.now();
        loggingService.logInbound("919876543210", "Hello no id", now, null);

        verify(repository, never()).existsByExternalMessageId(any());
        verify(repository).save(any(WhatsAppMessageLog.class));
    }

    @Test
    void testLogInbound_PropagatesDataIntegrityViolationOnConcurrentDuplicate() {
        when(repository.existsByExternalMessageId("wamid.concurrent")).thenReturn(false);
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("Duplicate key violation"));

        assertThrows(DataIntegrityViolationException.class, () ->
                loggingService.logInbound("919876543210", "Concurrent msg", LocalDateTime.now(), "wamid.concurrent")
        );
    }

    @Test
    void testLogInbound_PropagatesGenericException() {
        when(repository.existsByExternalMessageId("wamid.err")).thenThrow(new RuntimeException("DB down"));

        assertThrows(RuntimeException.class, () ->
                loggingService.logInbound("919876543210", "Fail msg", LocalDateTime.now(), "wamid.err")
        );
    }

    @Test
    void testLogOutbound_SavesSuccessfully() {
        LocalDateTime now = LocalDateTime.now();
        loggingService.logOutbound("919876543210", "Outbound text", now);

        ArgumentCaptor<WhatsAppMessageLog> captor = ArgumentCaptor.forClass(WhatsAppMessageLog.class);
        verify(repository).save(captor.capture());

        WhatsAppMessageLog saved = captor.getValue();
        assertEquals("919876543210", saved.getPhoneNumber());
        assertEquals("Outbound text", saved.getMessageBody());
        assertEquals(MessageDirection.OUTBOUND, saved.getDirection());
        assertNull(saved.getExternalMessageId());
    }

    @Test
    void testLogOutbound_PropagatesGenericException() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB connection error"));

        assertThrows(RuntimeException.class, () ->
                loggingService.logOutbound("919876543210", "Outbound text", LocalDateTime.now())
        );
    }

    @Test
    void testFormatInboundMessage_PrivacyAndFormatting() {
        // 1. Text message
        WhatsAppWebhookPayload.Message textMsg = new WhatsAppWebhookPayload.Message();
        textMsg.setType("text");
        WhatsAppWebhookPayload.TextPayload textPayload = new WhatsAppWebhookPayload.TextPayload();
        textPayload.setBody("Order groceries please");
        textMsg.setText(textPayload);
        assertEquals("Order groceries please", loggingService.formatInboundMessage(textMsg));

        // 2. Location message - privacy check: no raw lat/lng
        WhatsAppWebhookPayload.Message locMsg = new WhatsAppWebhookPayload.Message();
        locMsg.setType("location");
        WhatsAppWebhookPayload.LocationPayload locPayload = new WhatsAppWebhookPayload.LocationPayload();
        locPayload.setLatitude(12.9716);
        locPayload.setLongitude(77.5946);
        locPayload.setAddress("MG Road, Bangalore");
        locMsg.setLocation(locPayload);
        String formattedLoc = loggingService.formatInboundMessage(locMsg);
        assertFalse(formattedLoc.contains("12.9716"), "Must not leak raw latitude");
        assertTrue(formattedLoc.contains("[Customer shared location pin: MG Road, Bangalore]"));

        // 3. Interactive button with title
        WhatsAppWebhookPayload.Message btnMsg = new WhatsAppWebhookPayload.Message();
        btnMsg.setType("interactive");
        WhatsAppWebhookPayload.InteractivePayload interactivePayload = new WhatsAppWebhookPayload.InteractivePayload();
        WhatsAppWebhookPayload.InteractivePayload.ButtonReply buttonReply = new WhatsAppWebhookPayload.InteractivePayload.ButtonReply();
        buttonReply.setId("CONFIRM_101");
        buttonReply.setTitle("Confirm Order");
        interactivePayload.setButton_reply(buttonReply);
        btnMsg.setInteractive(interactivePayload);
        assertEquals("[Button: Confirm Order]", loggingService.formatInboundMessage(btnMsg));

        // 4. Interactive button fallback to ID when title is missing/blank
        buttonReply.setTitle("   ");
        assertEquals("[Button: CONFIRM_101]", loggingService.formatInboundMessage(btnMsg));

        // 5. Interactive button fallback when both title and id are missing
        buttonReply.setId("");
        assertEquals("[Button clicked]", loggingService.formatInboundMessage(btnMsg));
    }

    @Test
    void testLogInbound_FallbackTimestamp_UsesIstClockIndependentOfJvmTimezone() {
        when(repository.existsByExternalMessageId("wamid.fallback1")).thenReturn(false);

        Instant fixedInstant = Instant.parse("2026-10-18T10:30:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("Asia/Kolkata"));
        loggingService.setClock(fixedClock);

        TimeZone originalDefault = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            loggingService.logInbound("919876543210", "Hello fallback", null, "wamid.fallback1");

            ArgumentCaptor<WhatsAppMessageLog> captor = ArgumentCaptor.forClass(WhatsAppMessageLog.class);
            verify(repository).save(captor.capture());

            WhatsAppMessageLog saved = captor.getValue();
            assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0, 0), saved.getTimestamp(),
                    "Fallback timestamp must be 16:00:00 IST regardless of host JVM UTC timezone");
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }

    @Test
    void testLogOutbound_FallbackTimestamp_UsesIstClockIndependentOfJvmTimezone() {
        Instant fixedInstant = Instant.parse("2026-10-18T10:30:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("Asia/Kolkata"));
        loggingService.setClock(fixedClock);

        TimeZone originalDefault = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            loggingService.logOutbound("919876543210", "Outbound fallback", null);

            ArgumentCaptor<WhatsAppMessageLog> captor = ArgumentCaptor.forClass(WhatsAppMessageLog.class);
            verify(repository).save(captor.capture());

            WhatsAppMessageLog saved = captor.getValue();
            assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0, 0), saved.getTimestamp(),
                    "Fallback timestamp must be 16:00:00 IST regardless of host JVM UTC timezone");
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }
}
