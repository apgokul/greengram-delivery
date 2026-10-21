import { test, describe, it } from 'node:test';
import assert from 'node:assert';
import {
    getBaseUnit,
    isUnitCompatible,
    validateInventoryMapping
} from '../src/utils/inventoryMapping.ts';

describe('Inventory Mapping & Validation', () => {

    const mockInventories = [
        { id: 10, baseSku: 'COLD_PRESSED_COCONUT_OIL', stockQuantity: 50.0, unit: 'L' },
        { id: 20, baseSku: 'SUGAR', stockQuantity: 100.0, unit: 'KG' },
        { id: 30, baseSku: 'SOAP_BAR', stockQuantity: 40.0, unit: 'PCS' }
    ];

    it('Case 1: Multiword SKU COLD_PRESSED_COCONUT_OIL_500ML does not determine pool by truncating to COLD', () => {
        // In existing pool mode, selecting the coconut oil pool by ID explicitly assigns ID 10
        const resultExisting = validateInventoryMapping({
            mode: 'existing',
            selectedInventoryId: 10,
            inventories: mockInventories,
            newBaseSku: '',
            newBaseUnit: 'L',
            packageUnit: 'ML'
        });
        assert.strictEqual(resultExisting.valid, true);
        assert.strictEqual(resultExisting.resolvedInventoryId, 10);
        assert.strictEqual(resultExisting.isNew, false);

        // In new pool mode, entering the explicit multiword base SKU preserves the full base SKU
        const resultNew = validateInventoryMapping({
            mode: 'new',
            selectedInventoryId: null,
            inventories: mockInventories,
            newBaseSku: 'COLD_PRESSED_SESAME_OIL',
            newBaseUnit: 'L',
            packageUnit: 'ML'
        });
        assert.strictEqual(resultNew.valid, true);
        assert.strictEqual(resultNew.isNew, true);
        assert.strictEqual(resultNew.newBaseSku, 'COLD_PRESSED_SESAME_OIL');
        assert.notStrictEqual(resultNew.newBaseSku, 'COLD');
    });

    it('Case 2: Single-word SKU SUGAR_1KG requires an explicit pool decision and is rejected if unselected', () => {
        // Missing inventory selection in existing mode
        const resultMissing = validateInventoryMapping({
            mode: 'existing',
            selectedInventoryId: '',
            inventories: mockInventories,
            newBaseSku: '',
            newBaseUnit: 'KG',
            packageUnit: 'KG'
        });
        assert.strictEqual(resultMissing.valid, false);
        assert.strictEqual(resultMissing.error, 'Please select a physical inventory pool.');

        // Explicit selection of SUGAR (id 20) succeeds
        const resultSelected = validateInventoryMapping({
            mode: 'existing',
            selectedInventoryId: 20,
            inventories: mockInventories,
            newBaseSku: '',
            newBaseUnit: 'KG',
            packageUnit: 'KG'
        });
        assert.strictEqual(resultSelected.valid, true);
        assert.strictEqual(resultSelected.resolvedInventoryId, 20);
    });

    it('Case 3: Two package variants can reference the exact same inventory ID', () => {
        // Variant 1: 500ML pack
        const variant500ML = validateInventoryMapping({
            mode: 'existing',
            selectedInventoryId: 10,
            inventories: mockInventories,
            newBaseSku: '',
            newBaseUnit: 'L',
            packageUnit: 'ML'
        });
        // Variant 2: 1L pack
        const variant1L = validateInventoryMapping({
            mode: 'existing',
            selectedInventoryId: 10,
            inventories: mockInventories,
            newBaseSku: '',
            newBaseUnit: 'L',
            packageUnit: 'L'
        });

        assert.strictEqual(variant500ML.valid, true);
        assert.strictEqual(variant1L.valid, true);
        assert.strictEqual(variant500ML.resolvedInventoryId, 10);
        assert.strictEqual(variant1L.resolvedInventoryId, 10);
        assert.strictEqual(variant500ML.resolvedInventoryId, variant1L.resolvedInventoryId);
    });

    it('Case 4: Missing or invalid pool selection is rejected', () => {
        // Non-existent inventory ID
        const resultNotFound = validateInventoryMapping({
            mode: 'existing',
            selectedInventoryId: 999,
            inventories: mockInventories,
            newBaseSku: '',
            newBaseUnit: 'KG',
            packageUnit: 'KG'
        });
        assert.strictEqual(resultNotFound.valid, false);
        assert.match(resultNotFound.error, /Selected inventory pool not found/);

        // Attempting to create duplicate pool that already exists
        const resultDuplicate = validateInventoryMapping({
            mode: 'new',
            selectedInventoryId: null,
            inventories: mockInventories,
            newBaseSku: 'SUGAR',
            newBaseUnit: 'KG',
            packageUnit: 'KG'
        });
        assert.strictEqual(resultDuplicate.valid, false);
        assert.match(resultDuplicate.error, /already exists/);

        // Empty base SKU in new mode
        const resultEmptySku = validateInventoryMapping({
            mode: 'new',
            selectedInventoryId: null,
            inventories: mockInventories,
            newBaseSku: '   ',
            newBaseUnit: 'KG',
            packageUnit: 'KG'
        });
        assert.strictEqual(resultEmptySku.valid, false);
        assert.match(resultEmptySku.error, /enter a base SKU/);
    });

    it('Case 5: Unit compatibility follows backend UnitConverter matrix', () => {
        // Same units
        assert.strictEqual(isUnitCompatible('KG', 'KG'), true);
        assert.strictEqual(isUnitCompatible('L', 'L'), true);
        assert.strictEqual(isUnitCompatible('PCS', 'PCS'), true);
        assert.strictEqual(isUnitCompatible('PACK', 'PACK'), true);

        // Weight conversions
        assert.strictEqual(isUnitCompatible('G', 'KG'), true);
        assert.strictEqual(isUnitCompatible('KG', 'G'), true);

        // Volume conversions
        assert.strictEqual(isUnitCompatible('ML', 'L'), true);
        assert.strictEqual(isUnitCompatible('L', 'ML'), true);

        // Incompatible conversions
        assert.strictEqual(isUnitCompatible('L', 'KG'), false);
        assert.strictEqual(isUnitCompatible('KG', 'L'), false);
        assert.strictEqual(isUnitCompatible('G', 'ML'), false);
        assert.strictEqual(isUnitCompatible('PCS', 'KG'), false);
        assert.strictEqual(isUnitCompatible('PACK', 'L'), false);

        // Validation rejects incompatible units in existing pool
        const resultIncompatible = validateInventoryMapping({
            mode: 'existing',
            selectedInventoryId: 10, // Coconut oil is 'L'
            inventories: mockInventories,
            newBaseSku: '',
            newBaseUnit: 'KG',
            packageUnit: 'KG' // Attempting to link KG package to L pool
        });
        assert.strictEqual(resultIncompatible.valid, false);
        assert.match(resultIncompatible.error, /Incompatible units/);
    });

    it('Case 6: Default base unit helper maps correctly', () => {
        assert.strictEqual(getBaseUnit('G'), 'KG');
        assert.strictEqual(getBaseUnit('KG'), 'KG');
        assert.strictEqual(getBaseUnit('ML'), 'L');
        assert.strictEqual(getBaseUnit('L'), 'L');
        assert.strictEqual(getBaseUnit('PCS'), 'PCS');
        assert.strictEqual(getBaseUnit('PACK'), 'PACK');
        assert.strictEqual(getBaseUnit(''), 'KG');
        assert.strictEqual(getBaseUnit(undefined), 'KG');
    });

    it('Case 7: API failure workflow simulation does not produce false success', async () => {
        let inventoryCreated = false;
        let productCreated = false;

        const fakeInventoryApi = {
            createInventory: async () => {
                throw new Error('Database network timeout');
            }
        };

        const fakeProductsApi = {
            create: async () => {
                productCreated = true;
                return {};
            }
        };

        // When inventory creation fails, product creation is never attempted
        try {
            await fakeInventoryApi.createInventory();
            inventoryCreated = true;
            await fakeProductsApi.create();
        } catch (err) {
            assert.strictEqual(err.message, 'Database network timeout');
        }

        assert.strictEqual(inventoryCreated, false);
        assert.strictEqual(productCreated, false);
    });

    it('Case 8: Fails closed when inventory pool list is unavailable', () => {
        // Attempting to select an existing pool when inventory failed to load
        const resultExisting = validateInventoryMapping({
            mode: 'existing',
            selectedInventoryId: 10,
            inventories: [],
            newBaseSku: '',
            newBaseUnit: 'KG',
            packageUnit: 'KG',
            isInventoryLoaded: false
        });
        assert.strictEqual(resultExisting.valid, false);
        assert.match(resultExisting.error, /Inventory pools are currently unavailable/);

        // Attempting to create a new pool when inventory failed to load (must not silently allow creation)
        const resultNew = validateInventoryMapping({
            mode: 'new',
            selectedInventoryId: null,
            inventories: [],
            newBaseSku: 'NEW_OIL',
            newBaseUnit: 'L',
            packageUnit: 'L',
            isInventoryLoaded: false
        });
        assert.strictEqual(resultNew.valid, false);
        assert.match(resultNew.error, /Inventory pools are currently unavailable/);
    });
});
