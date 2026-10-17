package com.localcommerce.delivery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.service.BatchingService;
import com.localcommerce.delivery.service.OrderService;
import com.localcommerce.delivery.service.WarehouseService;
import com.localcommerce.delivery.utils.WebhookSignatureVerifier;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                eq("wamid.test999")
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
                eq("wamid.bsuid123")
        );

        // Outbound reply must use the exact BSUID
        verify(outboundClient).sendCatalogMessage(eq(bsuid));
    }
}

