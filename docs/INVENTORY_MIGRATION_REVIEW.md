# Inventory Migration Review (Phases 1-3)

This document provides a comprehensive architectural review of the inventory system migration implemented across Phases 1, 2, and 3. It details the schema evolution, the updated domain model, checkout concurrency architecture, and interim wiring implemented to decouple customer-facing product variants from physical backend stock.

---

## 1. Schema & Migration Strategy

### Final Table Structures
*   **`inventories`**: A new table representing the source of truth for physical stock.
    *   `id` (BIGSERIAL, PRIMARY KEY)
    *   `base_sku` (VARCHAR, UNIQUE)
    *   `stock_quantity` (NUMERIC(38, 3))
    *   `unit` (VARCHAR)
    *   `version` (BIGINT) - for optimistic locking (if used in future phases, currently concurrency relies on pessimistic locking).
*   **`products`**: The existing table representing customer-facing variants, modified as follows:
    *   `inventory_id` (BIGINT, FOREIGN KEY)
    *   *Removed*: `stock_quantity`
    *   *Renamed*: `quantity` → `package_quantity`
    *   *Renamed*: `unit` → `package_unit`

### Flyway Migration (`V2__introduce_shared_inventory.sql`)
The database migration executes a zero-downtime data transformation to extract physical inventory from variants. 

**Initial Population Math:**
The script seeds `inventories` by aggregating all variants grouped by a deduced `base_sku`. 
*   **SKU Parsing**: `CASE WHEN POSITION('_' IN sku) > 0 THEN SUBSTRING(sku FROM 1 FOR POSITION('_' IN sku) - 1) ELSE sku END` is used strictly as a one-time migration step to derive the `base_sku`.
*   **Aggregation (`SUM`)**: It converts smaller units to a standardized larger unit during aggregation using a nested `CASE` statement. `G` and `ML` variants have their quantities divided by `1000.0`.
*   **Unit Resolution**: Determines the aggregate unit (`KG` or `L`) by checking if any variants use weight or volume units.

**H2/PostgreSQL Compatible Update:**
To link `products` back to their newly created `inventories` row, a standard SQL subquery was implemented to satisfy strict parser requirements in H2 (used in test containers), bypassing PostgreSQL-specific `UPDATE ... FROM` syntax:
```sql
UPDATE products
SET inventory_id = (
    SELECT i.id FROM inventories i 
    WHERE (CASE WHEN POSITION('_' IN products.sku) > 0 THEN SUBSTRING(products.sku FROM 1 FOR POSITION('_' IN products.sku) - 1) ELSE products.sku END) = i.base_sku
);
```
Finally, old columns were dropped or renamed, and foreign keys/indexes were established.

---

## 2. Domain Model & Conversions

### Entities
*   **`Inventory.java`**: Represents a physical stock pool. Holds `id`, `baseSku`, `stockQuantity` (`BigDecimal`), `unit` (`ProductUnit`), and `version`.
*   **`Product.java`**: Represents a sellable SKU variant (e.g., "500G Chilli Powder"). 
    *   `packageQuantity` (Integer) and `packageUnit` (`ProductUnit`) define the package size.
    *   Maintains a `@ManyToOne` mapping: `private Inventory inventory;`.
    *   All direct stock-setting logic (e.g., `setStockQuantity`) was deleted.

### Conversions (`UnitConverter.java`)
A precise, deterministic conversion utility operates exclusively on `BigDecimal` with 3 decimal places (RoundingMode.HALF_UP).
*   **G ↔ KG**: Divides `G` by 1000 to get `KG`. Multiplies `KG` by 1000 to get `G`.
*   **ML ↔ L**: Divides `ML` by 1000 to get `L`. Multiplies `L` by 1000 to get `ML`.
*   Fails fast with `IllegalArgumentException` on unmapped or incompatible conversions (e.g., `G` to `L`).

---

## 3. Checkout & Concurrency Architecture (Critical)

The checkout process in `OrderService` was heavily refactored to treat `Inventory` as the primary locking subject, replacing the old `Product` variant lock.

### `processNewCart` (Cart Validation)
Validates the cart before taking payment or formalizing the order.
1.  Maps requested `CartItemDTO` quantities to their underlying `Product` and `Inventory`.
2.  Aquires `PESSIMISTIC_WRITE` locks on the shared `Inventory` records.
3.  Calculates whether the grouped inventory has sufficient stock.

### `finalizeOrderCheckout` (Atomic Deduction)
Executes within a manual `TransactionTemplate` boundary to strictly separate DB locking logic from external network calls (e.g., WhatsApp notifications).
1.  **Lock Strategy**:
    *   Extracts distinct `Inventory` entities associated with the cart's `OrderItem`s.
    *   **Deadlock Avoidance**: Sorts the `Inventory` entities strictly by `Inventory.id` (`.sorted(Comparator.comparing(Inventory::getId))`) before executing `inventoryRepo.findByIdForUpdate()`. This ensures all concurrent checkout threads acquire locks in a deterministic order.
2.  **Aggregation Math**:
    *   Iterates through each `OrderItem`. 
    *   Multiplies `Product.packageQuantity` by the requested cart quantity to get total physical package size.
    *   Converts this total physical size into the `Inventory` baseline unit using `UnitConverter`.
    *   Aggregates these converted quantities in a `Map<Inventory, BigDecimal>`.
3.  **Validation and Deduction**:
    *   Compares the aggregated requirement against `lockedInv.getStockQuantity()`.
    *   If insufficient, the entire transaction is rolled back and an `OrderValidationException` is thrown (Rejecting the cart atomically).
    *   Otherwise, the aggregated quantity is deducted: `lockedInv.setStockQuantity(lockedInv.getStockQuantity().subtract(required))`.

### Order Line Snapshots
The `OrderItem` entity deliberately maps back to the specific `Product` (the variant) rather than the generic `Inventory`. The `lockedPrice` and specific variant SKU are snapshotted in `OrderItem`. This ensures historic orders retain the exact price and variant size the customer originally selected.

---

## 4. Interim Admin & Warehouse Wiring

To ensure the application compiles and passes tests before Phase 4 & 5, administrative facades were temporarily re-wired to read stock levels indirectly from the new `Inventory` structure.

*   **`ProductAdminService` & `AdminProductController`**: Creating or updating products sets interim values, but stock creation strictly requires injecting a valid `Inventory` object. We updated the logic to fetch/inspect `product.getInventory().getStockQuantity()`.
*   **`WarehouseAlertService`**: `sendLowStockAlert` was updated to read from `product.getInventory().getStockQuantity()`. Furthermore, the `WhatsAppConstants.LOW_STOCK_ALERT` message string was patched to format a `%s` string value instead of an Integer `%d` because the stock is now represented by a `BigDecimal`.
*   **`WarehouseService`**: Temporary updates redirect any explicit stock visibility reads to the attached inventory to fulfill existing API contracts.
*   **`ProductRepository`**: Changed `findByIsActiveTrueAndStockQuantityGreaterThan(Integer)` to `findByIsActiveTrueAndInventory_StockQuantityGreaterThan(BigDecimal)` to leverage Spring Data's relationship traversal.

*Note: These administrative endpoints still expose variant-level stock concepts and will need to be formally decoupled in Phase 5.*

---

## 5. Testing

### Concurrency Guarantees (`OrderServiceConcurrencyIntegrationTest`)
The concurrency integration test suite was completely rewritten to reflect the new architecture.
*   **Scenario**: Two simultaneous checkout threads attempt to purchase overlapping variants linked to a single shared inventory constraint.
    *   Customer A attempts to buy "Wheat Flour - 1KG".
    *   Customer B attempts to buy "Wheat Flour - 500G" × 2.
    *   The shared physical inventory only has exactly 1 KG left.
*   **Outcome**: The test utilizes an `ExecutorService` and `CountDownLatch` to trigger both requests simultaneously.
*   **Verification**: 
    1.  Proves that `findByIdForUpdate` blocks competing threads safely.
    2.  Asserts that only **one** transaction succeeds.
    3.  Asserts that the other transaction strictly throws `OrderValidationException` and rolls back without causing partial inventory deduction.
    4.  Proves that deadlocks do not occur because of deterministic ID sorting.

---

## 6. Phase 8: Admin Frontend Adjustments

### TypeScript Interfaces
To align with the backend's explicit decoupling of variant data and inventory data, the frontend types were updated:
*   **`InventoryDTO`**: A new interface (`id`, `baseSku`, `stockQuantity`, `unit`) was introduced.
*   **`Product` interface**: Restructured to remove `stockQuantity` from the root, renaming `quantity` and `unit` to `packageQuantity` and `packageUnit`, and including a nested `inventory` property referencing `InventoryDTO`.

### Stock Adjustment API Shift
Stock adjustment logic was removed from the variant level and shifted to the core inventory level:
*   **Endpoint**: Changed from `PATCH /api/admin/products/{sku}/stock` to `PUT /api/admin/inventories/{inventoryId}/stock`.
*   **Payload**: Adjusted to send explicit physical stock values (e.g. `{ "stockQuantity": 8.4 }`) calculated client-side, rather than relative packet increments.

### UI Validation and Variant Creation
A critical UI change was made in `CreateProductModal.tsx`:
*   **Initial Stock Input Removed**: New product variants no longer accept initial stock quantities during creation. This enforces the separation between creating a sellable package size (the variant) and receiving physical warehouse inventory (the stock pool). Stock must now be established or modified explicitly via the new `AdminInventoryController` endpoints.

---

## 7. Phase 9: Catalog Simplification (Code Deletion)

### Dynamic Catalog Removal
We completely removed the dynamic `product_list` generation, the `groupAndSelectRepresentativeSkus` logic, and all Java-level unit filtering from the WhatsApp "Hi" flow. 

### Architectural Reasoning
Meta's native UI automatically groups variants and displays the entire catalog natively, regardless of which specific SKU is sent in a dynamic payload. Therefore, performing backend presentation filtering was entirely redundant and needlessly complex.

### Enforced Protection
Because inventory protection is strictly and safely enforced at the transaction boundary (during Phase 3 cart validation via pessimistic locks), we could confidently remove the frontend UI filtering. The "Hi" flow now exclusively sends a static text message directing the customer to tap the native WhatsApp Store icon, drastically reducing code overhead and potential edge cases.
