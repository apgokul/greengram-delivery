# GreenGram Project Context

This document serves as the authoritative technical handoff for the GreenGram quick-commerce delivery platform backend. It encapsulates the current state of the architecture, established business logic, and Phase 1 (MVP) decisions based on the existing codebase and development history.

---

## 1. Project Overview

* **What GreenGram is:** A local quick-commerce WhatsApp business that allows customers to order items (e.g., groceries, spice powders, oils) and have them delivered locally.
* **Business purpose:** To provide a seamless, native WhatsApp shopping experience without requiring users to download a separate app.
* **Current project status:** Phase 1 (MVP) core development is complete. The system has successfully passed local end-to-end testing via ngrok.
* **Current phase:** Pre-deployment / Final MVP QA.
* **What is already working:**
* WhatsApp webhook integration and message routing.
* Dynamic catalog generation with native variant handling (deduplication).
* Cart submission and validation.
* Location sharing, distance validation, and address reuse prompts.
* Pessimistic inventory locking and stock deduction upon confirmation.
* Interactive button flows for warehouse workers (Accept, Stock Issue, Ready).
* Pull-based rider dispatch system (via "DISPATCH" keyword).
* Generation of digital rider manifests and individual delivery cards.
* Global exception handling to prevent Meta API retry loops.


* **What is not yet complete:**
* Live cloud deployment (currently local via ngrok).
* Phase 2 features: Real-time Meta Commerce Manager graph API sync for out-of-stock items (currently out-of-stock items are simply hidden).
* Order cancellation flows (UNKNOWN/NEEDS VERIFICATION).



---

## 2. Technology Stack

* **Java version:** UNKNOWN (Requires verification from `pom.xml`/`build.gradle`).
* **Spring Boot version:** 3.x (Inferred from stack traces showing `jakarta.*` packages, `spring-webmvc-7.0.7`, and Tomcat 11.0.21).
* **Build Tool (Maven/Gradle):** UNKNOWN.
* **Database:** PostgreSQL (Driver version 42.7.10 observed).
* **ORM:** JPA / Hibernate Core 7.2.12.Final.
* **Connection Pool:** HikariCP 7.0.2.
* **Other backend libraries:** Lombok (`@Slf4j`, `@RequiredArgsConstructor`).
* **WhatsApp API:** Meta Cloud API (Graph API) for inbound webhooks and outbound messaging.
* **Frontend/admin technologies:** Appsmith (connected directly to the database for inventory and order management).
* **Deployment technologies:** Currently local + ngrok. Production hosting TBD (Render/AWS/DO discussed).
* **External services:** Meta/WhatsApp API. Twilio was explicitly excluded for Phase 1.

---

## 3. Repository / Project Structure

* `com.localcommerce.delivery.controller`: Contains `WhatsAppWebhookController` (handles all inbound Meta webhooks).
* `com.localcommerce.delivery.service`: Business logic layer.
* `OrderService`: Handles cart processing, location updates, and checkout.
* `WarehouseService`: Handles warehouse button taps (packing/ready).
* `WarehouseAlertService`: Handles outbound alerts to the store.
* `BatchingService`: Handles rider dispatch runs and manifest generation.
* `CustomerIdentityService`: Resolves customer profiles from inbound payloads.


* `com.localcommerce.delivery.repository`: Spring Data JPA interfaces (`CustomerRepository`, `ProductRepository`, `CustomerOrderRepository`, `DeliveryRunRepository`).
* `com.localcommerce.delivery.model`: JPA Entities (`Customer`, `CustomerOrder`, `Product`, `OrderItem`, `DeliveryRun`).
* `com.localcommerce.delivery.dto`: Data transfer objects (`CartItemDTO`, `OrderProcessing`, `WhatsAppWebhookPayload`).
* `com.localcommerce.delivery.enums`: Enums defining state machines (`OrderStatus`, `RiderStatus` - note: `Rider` entity was deleted, enum status may be unused).
* `com.localcommerce.delivery.client`: `WhatsAppOutboundClient` (handles REST calls to Meta Graph API).
* `com.localcommerce.delivery.exception`: `GlobalExceptionHandler` (catches crashes to protect the webhook).
* `com.localcommerce.delivery.utils`: `WhatsAppConstants`, `UserContext`, `DistanceUtil`, `DeliveryConstants`.

---

## 4. Database

### Entities and Tables

| Entity / Table | Purpose | Important Fields | Relationships | Notes |
| --- | --- | --- | --- | --- |
| `Customer` | Stores shopper identity and location. | `bsuid` (Meta ID), `whatsappNumber`, `recipientId`, `latitude`, `longitude` | One-to-Many with `CustomerOrder` | BSUID used for DB identity; actual phone number used for outbound routing. |
| `Product` | Represents catalog items/variants. | `sku`, `name`, `stockQuantity`, `currentPrice`, `active` | One-to-Many with `OrderItem` | SKU naming convention: `BaseName_Size` (e.g., `Chilli_100g`). |
| `CustomerOrder` | The core order lifecycle entity. | `status`, `totalAmount`, `deliveryLatitude`, `deliveryLongitude`, `createdAt`, `readyAt`, `deliveredAt` | Many-to-One with `Customer`, Many-to-One with `DeliveryRun`, One-to-Many with `OrderItem` | Enforces state transitions via `advanceStateTo()`. DB constraint `customer_orders_status_check` was removed/updated to allow smooth JPA updates. |
| `OrderItem` | Snapshot of purchased items. | `quantity`, `lockedPrice` | Many-to-One with `CustomerOrder`, Many-to-One with `Product` | Price is locked at the time of order creation. |
| `DeliveryRun` | Groups orders for a rider dispatch. | `status` (PENDING), `dispatchedAt`, `riderPhoneNumber` | One-to-Many with `CustomerOrder` | Links directly to a phone number string, avoiding the need for a complex Rider entity. |

*(Note: The `Rider` model was explicitly deleted to keep the MVP lean. Rider authorization is handled via properties).*

---

## 5. Backend Architecture

* **Controller Layer:** A single webhook controller (`WhatsAppWebhookController`) acts as the gateway. It routes payloads based on Meta's `messageType` (`TEXT`, `ORDER`, `LOCATION`, `INTERACTIVE`, `STATUS`).
* **Service Layer:** Separated by actor. Transactions are tightly controlled here using `@Transactional` to ensure row locks are held for the absolute minimum time required, specifically keeping HTTP API calls *outside* of transactional methods where possible.
* **Repository Layer:** Standard Spring Data JPA interfaces. Utilizes explicit pessimistic locking query: `findBySkuForUpdate()`.
* **Exception Handling:** A `@ControllerAdvice` (`GlobalExceptionHandler`) intercepts all unhandled exceptions. It logs the error, sends a fallback apology to the user, and **strictly returns an HTTP 200 OK** to Meta to prevent 24-hour webhook retry DDoS attacks.
* **Security:** Meta webhooks are verified via `hub.verify_token`. Role-based routing is handled manually: riders are authorized via a CSV list in application properties (`authorized.rider.numbers`), and warehouse access is restricted to a single configured number (`warehouse.phone.number`).
* **External Integrations:** Spring `RestTemplate` is used within `WhatsAppOutboundClient` to send messages to the Meta Graph API.

---

## 6. API Documentation

| Method | URL Path | Request | Response | Purpose | Auth |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/api/webhooks/whatsapp` | Query params: `hub.mode`, `hub.verify_token`, `hub.challenge` | String (challenge) | Meta Webhook Verification. | Token match against `application.properties`. |
| `POST` | `/api/webhooks/whatsapp` | JSON `WhatsAppWebhookPayload` | `200 OK` (Always) | Main entry point for all Meta messages and status updates. | Meta request payload structure. |

*(All internal communications occur via the single POST webhook. No custom REST APIs are currently exposed for frontends, as Appsmith connects directly to PostgreSQL).*

---

## 7. WhatsApp Integration

### Complete Flow:

1. **Incoming "Hi" (Customer):** Webhook catches `TEXT`. Groups active, in-stock products by base SKU (e.g., `Chilli` instead of sending all sizes). Sends interactive catalog.
2. **Cart Submission (Customer):** Webhook catches `ORDER`. `OrderService` validates stock using pessimistic locks. Creates a shell order in `CREATED` state. Outbound client prompts for location.
3. **Location Pin (Customer):** Webhook catches `LOCATION`. Calculates straight-line distance. Rejects if > `maxRadiusKm`. Saves to `CustomerOrder` and prompts with Order Summary and "Confirm Order" button.
4. **Checkout (Customer):** Webhook catches `INTERACTIVE` (`CONFIRM_CHECKOUT`). `OrderService` permanently deducts stock, sets status to `PENDING_WAREHOUSE`, and alerts the warehouse number.
5. **Packing (Warehouse):** Warehouse manager taps "Accept Order" -> status `PACKING`. Taps "Ready for Pickup" -> status `READY`.
6. **Dispatch (Rider):** Rider texts "DISPATCH". `BatchingService` creates a `DeliveryRun`, assigns `READY` orders, texts individual Delivery Cards, and sends one text-based summary Manifest. Status becomes `DISPATCHED`.
7. **Delivery (Rider):** Rider taps "Mark Delivered" on a Delivery Card. Status becomes `DELIVERED`. Customer gets a thank-you text.

---

## 8. Business Rules

* **Product Deduping:** To trigger Meta's native "See Options" UI for sizes/variants, only the first available SKU of a product group (split by `_`) is sent in the catalog message.
* **Stock Out Visibility:** Items with stock <= 0 are completely filtered out of the catalog message to avoid the "Cart Trap" (where Meta allows adding an item that is out of stock in the local DB).
* **Location Bounding:** Customers dropping pins outside `store.max.radius.km` receive an automatic rejection message.
* **24-Hour Rule Handling:** Meta Error 131047 (24-hour window constraint) is logged. Mitigation requires warehouse staff to text the bot daily.
* **Routing vs Identity:** `bsuid` is used exclusively for DB identity. `phoneNumber` is used exclusively for routing Meta outbound messages to avoid 131026 "Message undeliverable" errors.

---

## 9. Stock and Order Logic

* **When stock decreases:** Stock is formally deducted in `OrderService.finalizeOrderCheckout()`, which occurs *only* when the customer taps "Confirm Order".
* **Transaction Boundaries:** `processNewCart` utilizes pessimistic locking (`findBySkuForUpdate`) to validate cart feasibility.
* **Abandoned Carts:** Carts submitted but never confirmed remain in the `CREATED` state. Because deduction was moved to checkout confirmation, abandoned carts do not hold inventory hostage.
* **Low Stock Alerts:** Fired to the warehouse immediately when an item's stock dips below `DeliveryConstants.LOW_STOCK_THRESHOLD` during checkout.
* **Negative Stock:** Prevented by early returns in `processNewCart` and the checkout flow.
* **Cancellation Flow:** UNKNOWN. Code for manual order cancellation or restocking has not been explicitly defined in this context.

---

## 10. Admin Dashboard

* **Platform:** Appsmith.
* **Implementation:** Connected directly to the PostgreSQL database.
* **Capabilities:** Capable of viewing inventory, toggling active states, and manually updating stock quantities.
* **What was bypassed:** A custom Appsmith "Dispatch" modal was planned but intentionally bypassed in favor of a simpler text-based "DISPATCH" keyword sent via WhatsApp by the riders.

---

## 11. Security

* **API Security:** The webhook is verified by Meta using a static `whatsapp.verify.token`.
* **Actor Authorization:**
* Customers are bounded to their own carts via session/thread context (`UserContext.setSenderId`).
* Warehouse commands are protected by verifying the sender matches `warehouse.phone.number`.
* Rider commands are protected by checking the sender against the `authorized.rider.numbers` array.


* **Secrets:** API tokens and authorized numbers are injected via Spring `@Value` and must be provided as environment variables during deployment.

---

## 12. Deployment

* **Current state:** Running locally using ngrok to expose port 8080 to Meta.
* **Database:** Local PostgreSQL.
* **Future Configuration:** `application.properties` relies on environment variables (e.g., `${WHATSAPP_TOKEN}`).
* **Known requirements for live deployment:** Requires a cloud host (e.g., Render), a managed PostgreSQL database, and updating the Meta App dashboard with the live production webhook URL.

---

## 13. Current Development Status

| Feature | Status | Notes | Remaining Work |
| --- | --- | --- | --- |
| WhatsApp Webhook | Complete | Handles text, order, location, interactive, status | None |
| Product Catalog | Complete | Filters zero stock, deduplicates base SKUs | Phase 2: Meta Graph sync |
| Cart & Checkout | Complete | Stock validation and deduction logic applied | Order cancellation flow |
| Customer Location | Complete | Radius validation and db persistence implemented | None |
| Warehouse Flow | Complete | Alerts and state transitions working | None |
| Rider Dispatch | Complete | Manual batching via "DISPATCH" keyword working | Phase 2: Native Rider App |
| Database | Stable | Constraint modifications applied for JPA | None |
| Deployment | Incomplete | Tested via ngrok | Cloud hosting setup |

---

## 14. Important Past Decisions

* **Decision:** Delete the `Rider` entity model.
* **Reason:** Simplifies the MVP. Managing riders via a property list is faster for a tiny fleet than building a full DB/admin UI for them.


* **Decision:** Hide out-of-stock items instead of sending stock=0.
* **Reason:** Avoids the "WhatsApp Cart Trap" where Meta's static catalog allows users to add items that the local DB rejects.


* **Decision:** Move stock deduction from "Cart Received" to "Checkout Confirmed".
* **Reason:** Prevents abandoned carts from permanently deducting inventory.


* **Decision:** Return 200 OK in `GlobalExceptionHandler`.
* **Reason:** Forces Meta to drop failing webhooks instead of infinitely retrying and crashing the server.


* **Decision:** Separate `bsuid` from outbound routing.
* **Reason:** BSUID works for database lookups but fails as an outbound Meta recipient ID.



---

## 15. Known Problems

* **Meta 24-Hour Window (Error 131047):** The warehouse worker must message the bot at least once every 24 hours to keep the free-message window open, otherwise new order alerts will fail to send.
* **Catalog Image Clarity:** Meta aggressively compresses catalog images. Product images must be uploaded to Commerce Manager strictly at 1:1 ratio (500x500px).
* **Uncompleted Orders:** The database will accumulate shell orders in the `CREATED` state due to abandoned carts. (Needs a cron job for cleanup later).

---

## 16. Things NOT to Change

* **`GlobalExceptionHandler` returning `200 OK`:** Do not change this to a 500, or the Meta webhook will retry endlessly.
* **SKU String Splitting Logic:** The `.split("_")[0]` logic in the Webhook Controller is specifically designed to group product sizes natively in WhatsApp. Do not remove this without replacing it with Graph API sync logic.
* **Pessimistic Locking:** `findBySkuForUpdate` is critical to prevent race conditions when two users checkout simultaneously.

---

## 17. Immediate Next Steps

1. Provision cloud infrastructure (e.g., Render, Heroku) and a managed PostgreSQL database.
2. Set up environment variables on the host for database credentials, `whatsapp.verify.token`, `whatsapp.api.token`, `warehouse.phone.number`, and `authorized.rider.numbers`.
3. Deploy the Spring Boot application.
4. Update the Meta App Developer Dashboard with the new live webhook URL.
5. Conduct a final live end-to-end test in the production environment.

---

## 18. AI Agent Handoff Summary

**To Google Antigravity:**
You are taking over GreenGram, a Spring Boot 3 / PostgreSQL quick-commerce backend powered entirely by the WhatsApp Cloud API. The project is currently at the completion of Phase 1 MVP.

**What you must understand before modifying code:**

* The system uses a strictly event-driven state machine based on inbound Meta webhooks.
* Transactions are heavily isolated. HTTP outbound calls to Meta are intentionally kept outside of database `@Transactional` blocks to avoid locking tables during network latency.
* Identity (`bsuid`) and routing (`phoneNumber`) are strictly separated due to Meta API constraints.

**What you must not assume:**

* Do not assume the existence of a traditional frontend web app (admin is Appsmith, customer UI is native WhatsApp).
* Do not assume or implement Twilio fallbacks or complex rider tracking algorithms; they were explicitly rejected for this MVP.

**What should be verified:**

* Verify the exact Java version, build tool (Maven/Gradle), and property keys in the actual repository, as these were not explicitly detailed in the prior conversation context.