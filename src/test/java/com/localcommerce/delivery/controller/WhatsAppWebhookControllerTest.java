package com.localcommerce.delivery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.dto.CancellationResult;
import com.localcommerce.delivery.dto.OrderProcessing;
import com.localcommerce.delivery.enums.CancellationActor;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.service.BatchingService;
import com.localcommerce.delivery.service.OrderService;
import com.localcommerce.delivery.service.WarehouseService;
import com.localcommerce.delivery.utils.WebhookSignatureVerifier;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.mockito.ArgumentCaptor;
import java.util.Collections;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OrderService orderService;
    @Mock
    private WhatsAppOutboundClient outboundClient;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private BatchingService batchingService;
    @Mock
    private com.localcommerce.delivery.service.WhatsAppLoggingService loggingService;

    @InjectMocks
    private WhatsAppWebhookController controller;

    private final String appSecret = "test_secret_123";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "verifyToken", "test_verify_token");
        ReflectionTestUtils.setField(controller, "appSecret", appSecret);
        ReflectionTestUtils.setField(controller, "signatureVerificationEnabled", true);
        ReflectionTestUtils.setField(controller, "authorizedRiders", List.of("919999999999"));
        ReflectionTestUtils.setField(controller, "warehousePhoneNumber", "918888888888");

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private String generateSignature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return "sha256=" + sb.toString();
    }

    @Test
    void testValidSignatureAccepted() throws Exception {
        String payload = "{\"entry\": [{\"changes\": [{\"value\": {}}]}]}";
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());
    }

    @Test
    void testInvalidSignatureRejected() throws Exception {
        String payload = "{\"entry\": [{\"changes\": [{\"value\": {}}]}]}";
        String invalidSignature = "sha256=invalid123";

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", invalidSignature))
                .andExpect(status().isForbidden());
    }

    @Test
    void testMissingSignatureRejected() throws Exception {
        String payload = "{\"entry\": [{\"changes\": [{\"value\": {}}]}]}";

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
    }

    @Test
    void testStatusOnlyWebhookHandled() throws Exception {
        // Payload with statuses but NO messages
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "statuses": [
                              {
                                "id": "wamid.123",
                                "status": "failed",
                                "recipient_id": "919876543210",
                                "errors": [
                                  {
                                    "code": 131026,
                                    "title": "Message undeliverable",
                                    "message": "Message undeliverable"
                                  }
                                ]
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        // Verify that it didn't try to call any message processing dependencies
        verifyNoInteractions(orderService);
        verifyNoInteractions(outboundClient);
    }

    @Test
    void testCatalogTriggersStaticMessage() throws Exception {
        String payload = createTextPayload("Hi", "919876543210");
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(outboundClient).sendCatalogMessage("919876543210");
    }

    @Test
    void testWarehouseMessageTriggersInbox() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "contacts": [
                              {
                                "wa_id": "918888888888"
                              }
                            ],
                            "messages": [
                              {
                                "from": "918888888888",
                                "type": "text",
                                "text": {
                                  "body": "Hi"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(warehouseService).sendPendingOrdersInbox("918888888888");
    }

    @Test
    void testNonWarehouseMessageTriggersCustomerFlow() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "contacts": [
                              {
                                "wa_id": "917777777777"
                              }
                            ],
                            "messages": [
                              {
                                "from": "917777777777",
                                "type": "text",
                                "text": {
                                  "body": "Hi"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(warehouseService, never()).sendPendingOrdersInbox(anyString());
        // It should handle text message customer flow and send catalog message.
        verify(outboundClient).sendCatalogMessage("917777777777");
    }

    @Test
    void testWarehouseListReplyViewOrder() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "contacts": [
                              {
                                "wa_id": "918888888888"
                              }
                            ],
                            "messages": [
                              {
                                "from": "918888888888",
                                "type": "interactive",
                                "interactive": {
                                  "type": "list_reply",
                                  "list_reply": {
                                    "id": "VIEW_ORDER_1042"
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(warehouseService).processListReply("918888888888", "VIEW_ORDER_1042");
    }
    @Test
    void testConfirmCheckoutValidationFailure() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "contacts": [
                              {
                                "wa_id": "918888888888"
                              }
                            ],
                            "messages": [
                              {
                                "from": "918888888888",
                                "type": "interactive",
                                "interactive": {
                                  "type": "button_reply",
                                  "button_reply": {
                                    "id": "CHECKOUT_1042"
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        doThrow(new com.localcommerce.delivery.exception.OrderValidationException("Sorry, product is currently out of stock."))
                .when(orderService).finalizeOrderCheckout(1042L);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(orderService).finalizeOrderCheckout(1042L);
        verify(outboundClient).sendTextUpdate("918888888888", "Sorry, product is currently out of stock.");
    }

    private String createButtonReplyPayload(String buttonId, String fromNumber) {
        return """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "contacts": [
                              {
                                "wa_id": "%s"
                              }
                            ],
                            "messages": [
                              {
                                "from": "%s",
                                "type": "interactive",
                                "interactive": {
                                  "type": "button_reply",
                                  "button_reply": {
                                    "id": "%s"
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(fromNumber, fromNumber, buttonId);
    }

    @Test
    void testConfirmCheckout_Success_DoesNotSendDuplicatePackingAlert() throws Exception {
        String payload = createButtonReplyPayload("CONFIRM_1042", "918888888888");
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(orderService).finalizeOrderCheckout(1042L);
        // Verify duplicate packing alert is NOT sent by controller
        verify(outboundClient, never()).sendTextUpdate(eq("918888888888"), eq(WhatsAppConstants.ORDER_PACKING_ALERT));
    }

    @Test
    void testEditOrder_CreatedOrder_Success() throws Exception {
        String payload = createButtonReplyPayload("EDIT_ORDER_1042", "918888888888");
        String signature = generateSignature(payload);

        when(orderService.editOrder(1042L)).thenReturn(CancellationResult.success(null, CancellationActor.CUSTOMER, "Customer Edited Order"));

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(orderService).editOrder(1042L);
        verify(outboundClient, never()).sendCatalogMessage(any());
    }

    @Test
    void testEditOrder_AlreadyConfirmed_ReturnsFeedback() throws Exception {
        String payload = createButtonReplyPayload("EDIT_ORDER_1042", "918888888888");
        String signature = generateSignature(payload);

        when(orderService.editOrder(1042L)).thenReturn(CancellationResult.notAllowed(
                "This order is already being prepared or out for delivery and can no longer be edited.",
                null,
                CancellationActor.CUSTOMER
        ));

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(orderService).editOrder(1042L);
        verify(outboundClient).sendTextUpdate("918888888888", "This order is already being prepared or out for delivery and can no longer be edited.");
    }

    @Test
    void testCancelOrder_Customer_DeliveredOrder_ReturnsFeedback() throws Exception {
        String payload = createButtonReplyPayload("CANCEL_ORDER_1042", "918888888888");
        String signature = generateSignature(payload);

        when(orderService.cancelOrder(1042L, "Customer Cancelled", CancellationActor.CUSTOMER))
                .thenReturn(CancellationResult.alreadyDelivered(null, CancellationActor.CUSTOMER));

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(outboundClient).sendTextUpdate("918888888888", "This order has already been delivered and cannot be cancelled.");
    }

    @Test
    void testCancelOrder_Customer_AlreadyCancelled_ReturnsFeedback() throws Exception {
        String payload = createButtonReplyPayload("CANCEL_ORDER_1042", "918888888888");
        String signature = generateSignature(payload);

        when(orderService.cancelOrder(1042L, "Customer Cancelled", CancellationActor.CUSTOMER))
                .thenReturn(CancellationResult.alreadyCancelled(null, CancellationActor.CUSTOMER));

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(outboundClient).sendTextUpdate("918888888888", "This order is already cancelled.");
    }

    @Test
    void testRiderCancel_Delivered_FeedbackToRiderNotCustomer() throws Exception {
        String payload = createButtonReplyPayload(WhatsAppConstants.RIDER_CANCEL_REASON + "UNREACHABLE_1042", "919999999999");
        String signature = generateSignature(payload);

        when(orderService.cancelOrder(1042L, "Customer Unreachable", CancellationActor.RIDER))
                .thenReturn(CancellationResult.alreadyDelivered(null, CancellationActor.RIDER));

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        // Feedback is sent to rider (919999999999), not customer!
        verify(outboundClient).sendTextUpdate("919999999999", "⚠️ This order has already been delivered and cannot be cancelled.");
    }



    private String createTextPayload(String text, String fromNumber) {
        return """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "messages": [
                              {
                                "from": "%s",
                                "type": "text",
                                "text": {
                                  "body": "%s"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(fromNumber, text);
    }

    @Test
    void testReceiveMessage_LogsInboundMessage() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "messages": [
                              {
                                "id": "wamid.test999",
                                "from": "919876543210",
                                "type": "text",
                                "text": {
                                  "body": "Hi"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(loggingService).logInbound(
                eq("919876543210"),
                any(),
                any(),
                eq("wamid.test999"),
                isNull()
        );
    }

    @Test
    void testReceiveMessage_InboundDuplicateLogDoesNotBlockProcessing() throws Exception {
        String payload = createTextPayload("Hi", "919876543210");
        String signature = generateSignature(payload);

        doThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate key"))
                .when(loggingService).logInbound(any(), any(), any(), any());

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        // Catalog processing must still continue despite log failure
        verify(outboundClient).sendCatalogMessage("919876543210");
    }

    @Test
    void testReceiveMessage_InboundGenericLogFailureDoesNotBlockProcessing() throws Exception {
        String payload = createTextPayload("Hi", "919876543210");
        String signature = generateSignature(payload);

        doThrow(new RuntimeException("Database offline"))
                .when(loggingService).logInbound(any(), any(), any(), any());

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        // Catalog processing must still continue despite log failure
        verify(outboundClient).sendCatalogMessage("919876543210");
    }

    @Test
    void testReceiveMessage_PureBsuidWebhookPreservesIdentifierInboundAndOutbound() throws Exception {
        String bsuid = "bsuid.user_1042_abc";
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "contacts": [
                              {
                                "user_id": "%s",
                                "profile": {
                                  "name": "Jane Doe"
                                }
                              }
                            ],
                            "messages": [
                              {
                                "id": "wamid.bsuid123",
                                "from": "%s",
                                "type": "text",
                                "text": {
                                  "body": "Hi"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(bsuid, bsuid);

        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        // Inbound log must preserve the exact BSUID
        verify(loggingService).logInbound(
                eq(bsuid),
                any(),
                any(),
                eq("wamid.bsuid123"),
                eq("Jane Doe")
        );

        // Outbound reply must use the exact BSUID
        verify(outboundClient).sendCatalogMessage(eq(bsuid));
    }

    private String createTextPayloadWithTimestamp(String text, String fromNumber, String timestamp) {
        String tsField = timestamp != null ? "\"timestamp\": \"%s\",".formatted(timestamp) : "";
        return """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "messages": [
                              {
                                "id": "wamid.msg123",
                                "from": "%s",
                                %s
                                "type": "text",
                                "text": {
                                  "body": "%s"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(fromNumber, tsField, text);
    }

    @Test
    void testReceiveMessage_ConvertsMetaTimestampToCorrectIstTime() throws Exception {
        // Meta timestamp 1729247400 is 2024-10-18T10:30:00Z -> 2024-10-18T16:00:00 in IST (+05:30)
        String payload = createTextPayloadWithTimestamp("Hi", "919876543210", "1729247400");
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDateTime> timestampCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(loggingService).logInbound(
                eq("919876543210"),
                any(),
                timestampCaptor.capture(),
                eq("wamid.msg123"),
                isNull()
        );

        assertEquals(LocalDateTime.of(2024, 10, 18, 16, 0, 0), timestampCaptor.getValue());
    }

    @Test
    void testReceiveMessage_MissingMetaTimestampFallsBackToIstClockWithoutFailing() throws Exception {
        Instant fixedInstant = Instant.parse("2026-10-18T10:30:00Z");
        controller.setClock(Clock.fixed(fixedInstant, ZoneId.of("Asia/Kolkata")));

        // Payload with no timestamp field
        String payload = createTextPayloadWithTimestamp("Hi", "919876543210", null);
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDateTime> timestampCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(loggingService).logInbound(
                eq("919876543210"),
                any(),
                timestampCaptor.capture(),
                eq("wamid.msg123"),
                isNull()
        );

        // Fallback should be 16:00:00 IST for the fixed instant
        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0, 0), timestampCaptor.getValue());
        verify(outboundClient).sendCatalogMessage("919876543210");
    }

    @Test
    void testReceiveMessage_MalformedMetaTimestampFallsBackToIstClockWithoutFailing() throws Exception {
        Instant fixedInstant = Instant.parse("2026-10-18T10:30:00Z");
        controller.setClock(Clock.fixed(fixedInstant, ZoneId.of("Asia/Kolkata")));

        String payload = createTextPayloadWithTimestamp("Hi", "919876543210", "malformed_epoch_seconds");
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDateTime> timestampCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(loggingService).logInbound(
                eq("919876543210"),
                any(),
                timestampCaptor.capture(),
                eq("wamid.msg123"),
                isNull()
        );

        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0, 0), timestampCaptor.getValue());
        verify(outboundClient).sendCatalogMessage("919876543210");
    }

    @Test
    void testParseMetaTimestampToIst_UnitCases() {
        Instant fixedInstant = Instant.parse("2026-10-18T10:30:00Z");
        controller.setClock(Clock.fixed(fixedInstant, ZoneId.of("Asia/Kolkata")));

        // Valid timestamp
        assertEquals(LocalDateTime.of(2024, 10, 18, 16, 0, 0),
                controller.parseMetaTimestampToIst("1729247400"));

        // Null, blank, malformed
        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0, 0),
                controller.parseMetaTimestampToIst(null));
        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0, 0),
                controller.parseMetaTimestampToIst("   "));
        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0, 0),
                controller.parseMetaTimestampToIst("invalid_seconds"));
    }

    @Test
    void testReceiveMessage_ProcessingExceptionDispatchesApologyAndCleansUpContext() throws Exception {
        String payload = createTextPayload("Hi", "919876543210");
        String signature = generateSignature(payload);

        doThrow(new RuntimeException("Simulated unexpected failure during catalog send"))
                .when(outboundClient).sendCatalogMessage("919876543210");

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        // Must dispatch fallback apology to customer
        verify(outboundClient).sendTextUpdate(
                eq("919876543210"),
                eq(com.localcommerce.delivery.exception.GlobalExceptionHandler.FALLBACK_APOLOGY_MESSAGE)
        );

        // UserContext must be cleared and not leaked to the thread
        assertNull(com.localcommerce.delivery.utils.UserContext.getPhoneNumber());
    }

    @Test
    void testReceiveMessage_PostCommitOrderPromptFailure_DoesNotSendApology() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "contacts": [
                              {
                                "wa_id": "919876543210",
                                "profile": {
                                  "name": "John Doe"
                                }
                              }
                            ],
                            "messages": [
                              {
                                "from": "919876543210",
                                "id": "wamid.order123",
                                "type": "order",
                                "order": {
                                  "catalog_id": "cat_123",
                                  "product_items": [
                                    {
                                      "product_retailer_id": "RICE-1KG",
                                      "quantity": "2"
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        Customer customer = new Customer();
        customer.setWhatsappNumber("919876543210");

        CustomerOrder savedOrder = new CustomerOrder();
        savedOrder.setId(1042L);
        savedOrder.setDisplayId("ORD-1042");
        savedOrder.setCustomer(customer);

        OrderProcessing.OrderProcessResult successResult = OrderProcessing.OrderProcessResult.success(savedOrder);
        when(orderService.processNewCart(any(), any(), any(), any())).thenReturn(successResult);

        // Outbound invoice prompt fails post-commit (e.g. Meta API network timeout)
        doThrow(new RuntimeException("Meta API timeout"))
                .when(outboundClient).sendOrderSummaryPrompt(eq("919876543210"), eq(savedOrder));

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        // CRUCIAL: Must NEVER send fallback apology telling customer to restart, because the order is committed!
        verify(outboundClient, never()).sendTextUpdate(
                eq("919876543210"),
                eq(com.localcommerce.delivery.exception.GlobalExceptionHandler.FALLBACK_APOLOGY_MESSAGE)
        );

        // Context must be cleared
        assertNull(com.localcommerce.delivery.utils.UserContext.getPhoneNumber());
    }

    @Test
    void testInteractiveButtonReply_ReuseSavedLocation_PassesSenderPhoneNumber() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "messages": [
                              {
                                "from": "919876543210",
                                "id": "wamid.btn_reuse_123",
                                "type": "interactive",
                                "interactive": {
                                  "type": "button_reply",
                                  "button_reply": {
                                    "id": "REUSE_LOC_1042",
                                    "title": "Use Saved Location"
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(orderService, times(1)).confirmSavedLocation(1042L, "919876543210");
    }

    @Test
    void testInteractiveButtonReply_SetLocSingle_PassesSenderPhoneNumber() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "messages": [
                              {
                                "from": "919876543210",
                                "id": "wamid.btn_setloc_123",
                                "type": "interactive",
                                "interactive": {
                                  "type": "button_reply",
                                  "button_reply": {
                                    "id": "SETLOC_1042_10.010_76.210",
                                    "title": "Set Location"
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(orderService, times(1)).assignLocationToOrder(1042L, "919876543210", 10.010, 76.210);
    }

    @Test
    void testInteractiveButtonReply_SetLocAll_PassesSenderPhoneNumber() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "messages": [
                              {
                                "from": "919876543210",
                                "id": "wamid.btn_setloc_all_123",
                                "type": "interactive",
                                "interactive": {
                                  "type": "button_reply",
                                  "button_reply": {
                                    "id": "SETLOC_ALL_10.010_76.210_1042_1043",
                                    "title": "Set Location for All"
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(orderService, times(1)).assignLocationToAllOrders(List.of(1042L, 1043L), "919876543210", 10.010, 76.210);
    }

    @Test
    void testInboundWebhook_PassesProfileNameToLoggingService() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "id": "12345",
                      "changes": [
                        {
                          "value": {
                            "contacts": [
                              {
                                "profile": {
                                  "name": "Anu Joseph"
                                },
                                "wa_id": "919876543210"
                              }
                            ],
                            "messages": [
                              {
                                "from": "919876543210",
                                "id": "wamid.text_profile_test",
                                "timestamp": "1729837200",
                                "type": "text",
                                "text": {
                                  "body": "Hello, do you have fresh green gram?"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(loggingService).logInbound(
                eq("919876543210"),
                any(),
                any(),
                eq("wamid.text_profile_test"),
                eq("Anu Joseph")
        );
    }

    @Test
    void testInboundWebhook_WithoutProfileName_PassesNullToLoggingService() throws Exception {
        String payload = """
                {
                  "entry": [
                    {
                      "id": "12345",
                      "changes": [
                        {
                          "value": {
                            "messages": [
                              {
                                "from": "919876543210",
                                "id": "wamid.text_no_profile",
                                "timestamp": "1729837200",
                                "type": "text",
                                "text": {
                                  "body": "Hi there"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
        String signature = generateSignature(payload);

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Hub-Signature-256", signature))
                .andExpect(status().isOk());

        verify(loggingService).logInbound(
                eq("919876543210"),
                any(),
                any(),
                eq("wamid.text_no_profile"),
                isNull()
        );
    }
}
