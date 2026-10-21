import type { InventoryDTO } from '../types/product.ts';

export interface ValidateInventoryMappingParams {
    mode: 'existing' | 'new';
    selectedInventoryId: number | '' | null;
    inventories: InventoryDTO[];
    newBaseSku: string;
    newBaseUnit: string;
    packageUnit?: string;
    isInventoryLoaded?: boolean;
}

export interface InventoryMappingResult {
    valid: boolean;
    error?: string;
    resolvedInventoryId?: number;
    isNew: boolean;
    newBaseSku?: string;
    newBaseUnit?: string;
}

/**
 * Maps a product packaging unit to its canonical default physical inventory base unit.
 */
export function getBaseUnit(packUnit?: string): string {
    if (!packUnit) return 'KG';
    const unit = packUnit.toUpperCase();
    if (unit === 'G' || unit === 'KG') return 'KG';
    if (unit === 'ML' || unit === 'L') return 'L';
    return unit; // e.g., PCS, PACK
}

/**
 * Checks if a product's package unit is compatible with an inventory pool's base unit,
 * conforming strictly to the backend UnitConverter conversion matrix:
 * - Equal units are always compatible (e.g. KG <-> KG, L <-> L, PCS <-> PCS, PACK <-> PACK).
 * - Weight units are compatible: G <-> KG.
 * - Volume units are compatible: ML <-> L.
 * - Incompatible pairs (e.g. weight <-> volume, discrete <-> metric) are rejected.
 */
export function isUnitCompatible(packUnit?: string, invUnit?: string): boolean {
    if (!packUnit || !invUnit) return true; // if units are unspecified, do not artificially reject
    const p = packUnit.toUpperCase();
    const i = invUnit.toUpperCase();

    if (p === i) return true;
    if ((p === 'G' && i === 'KG') || (p === 'KG' && i === 'G')) return true;
    if ((p === 'ML' && i === 'L') || (p === 'L' && i === 'ML')) return true;

    return false;
}

/**
 * Validates inventory mapping for product creation.
 * Replaces the unsafe SKU-splitting heuristic with explicit pool selection or explicit pool creation.
 */
export function validateInventoryMapping(params: ValidateInventoryMappingParams): InventoryMappingResult {
    const { mode, selectedInventoryId, inventories, newBaseSku, newBaseUnit, packageUnit, isInventoryLoaded } = params;

    if (isInventoryLoaded === false) {
        return {
            valid: false,
            isNew: false,
            error: 'Inventory pools are currently unavailable. Please reload the inventory list before continuing.'
        };
    }

    if (mode === 'existing') {
        if (!selectedInventoryId || typeof selectedInventoryId !== 'number' || selectedInventoryId <= 0) {
            return {
                valid: false,
                isNew: false,
                error: 'Please select a physical inventory pool.'
            };
        }

        const pool = inventories.find(inv => inv.id === Number(selectedInventoryId));
        if (!pool) {
            return {
                valid: false,
                isNew: false,
                error: 'Selected inventory pool not found. Please choose an existing pool from the list.'
            };
        }

        if (packageUnit && pool.unit && !isUnitCompatible(packageUnit, pool.unit)) {
            return {
                valid: false,
                isNew: false,
                error: `Incompatible units: package unit '${packageUnit}' cannot be converted to inventory pool unit '${pool.unit}'.`
            };
        }

        return {
            valid: true,
            isNew: false,
            resolvedInventoryId: pool.id
        };
    }

    if (mode === 'new') {
        const trimmedSku = (newBaseSku || '').trim().toUpperCase();
        if (!trimmedSku) {
            return {
                valid: false,
                isNew: true,
                error: 'Please enter a base SKU for the new inventory pool.'
            };
        }

        const trimmedUnit = (newBaseUnit || '').trim().toUpperCase();
        if (!trimmedUnit) {
            return {
                valid: false,
                isNew: true,
                error: 'Please select a unit for the new inventory pool.'
            };
        }

        if (packageUnit && !isUnitCompatible(packageUnit, trimmedUnit)) {
            return {
                valid: false,
                isNew: true,
                error: `Incompatible units: package unit '${packageUnit}' cannot be converted to inventory pool unit '${trimmedUnit}'.`
            };
        }

        // Prevent duplicate pool creation if baseSku already exists
        const duplicate = inventories.find(inv => inv.baseSku.toUpperCase() === trimmedSku);
        if (duplicate) {
            return {
                valid: false,
                isNew: true,
                error: `An inventory pool with base SKU '${trimmedSku}' already exists (ID: ${duplicate.id}). Please select it from existing pools.`
            };
        }

        return {
            valid: true,
            isNew: true,
            newBaseSku: trimmedSku,
            newBaseUnit: trimmedUnit
        };
    }

    return {
        valid: false,
        isNew: false,
        error: 'Invalid inventory pool mode.'
    };
}
