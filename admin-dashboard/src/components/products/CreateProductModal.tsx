import React, { useState, useEffect } from 'react';
import { Modal } from '../ui/Modal';
import { CreateProductRequest, InventoryDTO } from '../../types/product';
import { productsApi } from '../../api/products';
import { inventoryApi } from '../../api/inventories';
import { getBaseUnit, validateInventoryMapping } from '../../utils/inventoryMapping';

interface CreateProductModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess: () => void;
}

export const CreateProductModal: React.FC<CreateProductModalProps> = ({ isOpen, onClose, onSuccess }) => {
    const [inventories, setInventories] = useState<InventoryDTO[]>([]);

    // Product form state
    const [formData, setFormData] = useState<Omit<CreateProductRequest, 'inventoryId'>>({
        sku: '',
        name: '',
        currentPrice: 0,
        packageQuantity: 1,
        packageUnit: 'KG',
        active: true
    });

    // Explicit inventory pool selection state
    const [inventoryMode, setInventoryMode] = useState<'existing' | 'new'>('existing');
    const [selectedInventoryId, setSelectedInventoryId] = useState<number | ''>('');
    const [newBaseSku, setNewBaseSku] = useState('');
    const [newBaseUnit, setNewBaseUnit] = useState('KG');

    // Loading & error states
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [inventoryLoading, setInventoryLoading] = useState(false);
    const [inventoryLoadError, setInventoryLoadError] = useState<string | null>(null);

    const resetForm = () => {
        setFormData({
            sku: '',
            name: '',
            currentPrice: 0,
            packageQuantity: 1,
            packageUnit: 'KG',
            active: true
        });
        setSelectedInventoryId('');
        setNewBaseSku('');
        setNewBaseUnit('KG');
        setInventoryMode('existing');
        setError(null);
        setInventoryLoadError(null);
    };

    const fetchInventories = async () => {
        setInventoryLoading(true);
        setInventoryLoadError(null);
        try {
            const data = await inventoryApi.getAllInventories();
            setInventories(data);
            setInventoryLoadError(null);
            if (data.length === 0) {
                setInventoryMode('new');
            } else {
                setInventoryMode('existing');
            }
        } catch (err: any) {
            console.error("Failed to fetch inventories:", err);
            setInventoryLoadError("Failed to load existing inventory pools. Product creation is disabled until pools are loaded.");
        } finally {
            setInventoryLoading(false);
        }
    };

    useEffect(() => {
        if (isOpen) {
            resetForm();
            fetchInventories();
        }
    }, [isOpen]);

    const handlePackageUnitChange = (unit: string) => {
        setFormData(prev => ({ ...prev, packageUnit: unit }));
        if (inventoryMode === 'new') {
            setNewBaseUnit(getBaseUnit(unit));
        }
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (loading || inventoryLoading) return; // Prevent duplicate submissions
        setError(null);

        // Fail-closed guard: inventory pools must be loaded successfully
        if (inventoryLoadError) {
            setError("Cannot create product: existing inventory pools could not be loaded. Please click Retry.");
            return;
        }

        if (!formData.sku.trim() || !formData.name.trim() || formData.currentPrice <= 0) {
            setError("Please fill out all required fields with valid values.");
            return;
        }

        // Validate inventory mapping explicitly (removes unsafe SKU-splitting heuristic)
        const mappingValidation = validateInventoryMapping({
            mode: inventoryMode,
            selectedInventoryId,
            inventories,
            newBaseSku,
            newBaseUnit,
            packageUnit: formData.packageUnit,
            isInventoryLoaded: !inventoryLoading && !inventoryLoadError
        });

        if (!mappingValidation.valid) {
            setError(mappingValidation.error || "Invalid inventory pool selection.");
            return;
        }

        setLoading(true);
        let inventoryId: number;

        try {
            if (mappingValidation.isNew) {
                // Step 1: Create new inventory pool with explicit base SKU and unit
                let newInv: InventoryDTO;
                try {
                    newInv = await inventoryApi.createInventory({
                        baseSku: mappingValidation.newBaseSku!,
                        unit: mappingValidation.newBaseUnit!
                    });
                } catch (err: any) {
                    setError(`Failed to create inventory pool: ${err.message || 'Unknown error'}`);
                    setLoading(false);
                    return;
                }

                inventoryId = newInv.id;
                // Update local inventory list and link form state so retries use this pool
                setInventories(prev => [...prev, newInv]);
                setSelectedInventoryId(newInv.id);
                setInventoryMode('existing');
            } else {
                inventoryId = mappingValidation.resolvedInventoryId!;
            }

            // Step 2: Create Product Variant referencing the authoritative inventory ID
            try {
                await productsApi.create({
                    ...formData,
                    sku: formData.sku.trim(),
                    name: formData.name.trim(),
                    inventoryId,
                    packageQuantity: formData.packageQuantity ? Number(formData.packageQuantity) : undefined,
                    packageUnit: formData.packageUnit || undefined
                });
            } catch (err: any) {
                if (mappingValidation.isNew) {
                    setError(`Inventory pool '${mappingValidation.newBaseSku}' was created (ID: ${inventoryId}), but product variant creation failed: ${err.message || 'Unknown error'}. You can retry creating the variant using this existing pool.`);
                } else {
                    setError(err.message || 'Failed to create product');
                }
                setLoading(false);
                return;
            }

            onSuccess();
            onClose();
            resetForm();
        } catch (err: any) {
            setError(err.message || 'An unexpected error occurred');
        } finally {
            setLoading(false);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} title="Add New Product Variant">
            <form onSubmit={handleSubmit}>
                <div className="modal-body">
                    {error && <div className="alert alert-error">{error}</div>}

                    <div className="input-group">
                        <label className="input-label">Variant SKU</label>
                        <input 
                            type="text" 
                            className="input-field" 
                            value={formData.sku}
                            onChange={e => setFormData({ ...formData, sku: e.target.value })}
                            placeholder="e.g. COLD_PRESSED_COCONUT_OIL_500ML"
                            required
                            disabled={loading}
                        />
                    </div>
                    
                    <div className="input-group">
                        <label className="input-label">Display Name</label>
                        <input 
                            type="text" 
                            className="input-field" 
                            value={formData.name}
                            onChange={e => setFormData({ ...formData, name: e.target.value })}
                            placeholder="e.g. Cold Pressed Coconut Oil"
                            required
                            disabled={loading}
                        />
                    </div>
                    
                    <div className="input-group">
                        <label className="input-label">Price (₹)</label>
                        <input 
                            type="number" 
                            step="0.01"
                            className="input-field" 
                            value={formData.currentPrice || ''}
                            onChange={e => setFormData({ ...formData, currentPrice: parseFloat(e.target.value) })}
                            required
                            disabled={loading}
                        />
                    </div>

                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                        <div className="input-group">
                            <label className="input-label">Pack Size / Quantity</label>
                            <input 
                                type="number" 
                                step="0.01"
                                className="input-field" 
                                value={formData.packageQuantity || ''}
                                onChange={e => setFormData({ ...formData, packageQuantity: parseFloat(e.target.value) })}
                                min="0.01"
                                disabled={loading}
                            />
                        </div>
                        <div className="input-group">
                            <label className="input-label">Pack Unit</label>
                            <select 
                                className="input-field"
                                value={formData.packageUnit || ''}
                                onChange={e => handlePackageUnitChange(e.target.value)}
                                disabled={loading}
                            >
                                <option value="">None</option>
                                <option value="G">G (Grams)</option>
                                <option value="KG">KG (Kilograms)</option>
                                <option value="ML">ML (Milliliters)</option>
                                <option value="L">L (Liters)</option>
                                <option value="PCS">PCS (Pieces)</option>
                                <option value="PACK">PACK</option>
                            </select>
                        </div>
                    </div>

                    {/* Explicit Physical Inventory Pool Selection */}
                    <div className="input-group" style={{ marginTop: '0.5rem', marginBottom: '1.25rem', padding: '1rem', border: '1px solid var(--border-color)', borderRadius: '0.5rem', backgroundColor: 'rgba(255, 255, 255, 0.02)' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.25rem' }}>
                            <label className="input-label" style={{ fontWeight: 600, color: 'var(--text-primary)', marginBottom: 0 }}>
                                Physical Inventory Pool
                            </label>
                            {inventoryLoadError && (
                                <button
                                    type="button"
                                    className="btn btn-secondary btn-sm"
                                    onClick={fetchInventories}
                                    disabled={inventoryLoading}
                                >
                                    {inventoryLoading ? 'Retrying...' : '↻ Retry'}
                                </button>
                            )}
                        </div>
                        <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: 0, marginBottom: '0.75rem' }}>
                            Select an existing stock pool to share physical inventory with other package sizes, or create a new pool.
                        </p>

                        {inventoryLoading && (
                            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', padding: '0.75rem 0', color: 'var(--text-secondary)', fontSize: '0.875rem' }}>
                                <div className="spinner" style={{ width: '1rem', height: '1rem', borderWidth: '2px' }}></div>
                                Loading inventory pools...
                            </div>
                        )}

                        {inventoryLoadError && !inventoryLoading && (
                            <div className="alert alert-error" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                                <span>{inventoryLoadError}</span>
                                <button
                                    type="button"
                                    className="btn btn-secondary btn-sm"
                                    onClick={fetchInventories}
                                    style={{ marginLeft: '1rem', whiteSpace: 'nowrap' }}
                                >
                                    Retry
                                </button>
                            </div>
                        )}

                        {!inventoryLoading && !inventoryLoadError && (
                            <>
                                <div style={{ display: 'flex', gap: '1.5rem', marginBottom: '0.75rem' }}>
                                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.375rem', cursor: 'pointer', fontSize: '0.875rem' }}>
                                        <input
                                            type="radio"
                                            name="inventoryMode"
                                            value="existing"
                                            checked={inventoryMode === 'existing'}
                                            onChange={() => { setInventoryMode('existing'); setError(null); }}
                                            disabled={loading}
                                        />
                                        Existing Pool
                                    </label>
                                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.375rem', cursor: 'pointer', fontSize: '0.875rem' }}>
                                        <input
                                            type="radio"
                                            name="inventoryMode"
                                            value="new"
                                            checked={inventoryMode === 'new'}
                                            onChange={() => { setInventoryMode('new'); setError(null); }}
                                            disabled={loading}
                                        />
                                        + Create New Pool
                                    </label>
                                </div>

                                {inventoryMode === 'existing' ? (
                                    <div className="input-group" style={{ marginBottom: 0 }}>
                                        <select
                                            className="input-field"
                                            value={selectedInventoryId}
                                            onChange={e => {
                                                setSelectedInventoryId(e.target.value ? Number(e.target.value) : '');
                                                setError(null);
                                            }}
                                            disabled={loading}
                                        >
                                            <option value="">-- Select an inventory pool --</option>
                                            {inventories.map(inv => (
                                                <option key={inv.id} value={inv.id}>
                                                    {inv.baseSku} ({inv.unit}) — Stock: {inv.stockQuantity}
                                                </option>
                                            ))}
                                        </select>
                                    </div>
                                ) : (
                                    <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '0.75rem' }}>
                                        <div className="input-group" style={{ marginBottom: 0 }}>
                                            <label className="input-label" style={{ fontSize: '0.75rem' }}>Base Inventory SKU</label>
                                            <input
                                                type="text"
                                                className="input-field"
                                                value={newBaseSku}
                                                onChange={e => { setNewBaseSku(e.target.value); setError(null); }}
                                                placeholder="e.g. COLD_PRESSED_COCONUT_OIL"
                                                disabled={loading}
                                            />
                                        </div>
                                        <div className="input-group" style={{ marginBottom: 0 }}>
                                            <label className="input-label" style={{ fontSize: '0.75rem' }}>Base Unit</label>
                                            <select
                                                className="input-field"
                                                value={newBaseUnit}
                                                onChange={e => { setNewBaseUnit(e.target.value); setError(null); }}
                                                disabled={loading}
                                            >
                                                <option value="KG">KG</option>
                                                <option value="G">G</option>
                                                <option value="L">L</option>
                                                <option value="ML">ML</option>
                                                <option value="PCS">PCS</option>
                                                <option value="PACK">PACK</option>
                                            </select>
                                        </div>
                                    </div>
                                )}
                            </>
                        )}
                    </div>

                    <div className="input-group" style={{ flexDirection: 'row', alignItems: 'center', gap: '0.5rem', marginTop: '0.5rem' }}>
                        <input 
                            type="checkbox" 
                            id="isActive"
                            checked={formData.active}
                            onChange={e => setFormData({ ...formData, active: e.target.checked })}
                            disabled={loading}
                        />
                        <label htmlFor="isActive" className="input-label" style={{ marginBottom: 0 }}>Variant is Active</label>
                    </div>
                </div>
                <div className="modal-footer">
                    <button type="button" className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
                    <button
                        type="submit"
                        className="btn btn-primary"
                        disabled={loading || inventoryLoading || !!inventoryLoadError}
                        title={inventoryLoadError ? "Inventory pools must be loaded before creating a product" : undefined}
                    >
                        {loading ? <div className="spinner"></div> : 'Create Variant'}
                    </button>
                </div>
            </form>
        </Modal>
    );
};
