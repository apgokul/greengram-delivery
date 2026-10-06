import React, { useState, useEffect } from 'react';
import { Modal } from '../ui/Modal';
import { CreateProductRequest, InventoryDTO } from '../../types/product';
import { productsApi } from '../../api/products';
import { inventoryApi } from '../../api/inventories';

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
    
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (isOpen) {
            fetchInventories();
        }
    }, [isOpen]);

    const fetchInventories = async () => {
        try {
            const data = await inventoryApi.getAllInventories();
            setInventories(data);
        } catch (err) {
            console.error("Failed to fetch inventories:", err);
        }
    };

    const getBaseUnit = (packUnit: string) => {
        const unit = packUnit.toUpperCase();
        if (unit === 'G' || unit === 'KG') return 'KG';
        if (unit === 'ML' || unit === 'L') return 'L';
        return unit; // fallback for PCS, PACK, etc.
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);

        if (!formData.sku || !formData.name || formData.currentPrice <= 0) {
            setError("Please fill out all required fields with valid values.");
            return;
        }

        setLoading(true);
        try {
            // Extract Base SKU and Determine Base Unit
            const extractedBaseSku = formData.sku.split('_')[0] || formData.sku;
            const baseUnit = getBaseUnit(formData.packageUnit || 'KG');

            // Find or Create Inventory
            let inventoryId: number;
            const existingInventory = inventories.find(inv => inv.baseSku === extractedBaseSku);

            if (existingInventory) {
                inventoryId = existingInventory.id;
            } else {
                const newInv = await inventoryApi.createInventory({
                    baseSku: extractedBaseSku,
                    unit: baseUnit
                });
                inventoryId = newInv.id;
                
                // Update local state to avoid refetching immediately if they create another
                setInventories(prev => [...prev, newInv]);
            }

            // Create Product
            await productsApi.create({
                ...formData,
                inventoryId,
                packageQuantity: formData.packageQuantity ? Number(formData.packageQuantity) : undefined,
                packageUnit: formData.packageUnit || undefined
            });
            
            onSuccess();
            onClose();
            // Reset form
            setFormData({ sku: '', name: '', currentPrice: 0, packageQuantity: 1, packageUnit: 'KG', active: true });
        } catch (err: any) {
            setError(err.message || 'Failed to create product');
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
                            onChange={e => setFormData({...formData, sku: e.target.value})}
                            placeholder="e.g. SUGAR_1KG"
                            required
                        />
                    </div>
                    
                    <div className="input-group">
                        <label className="input-label">Display Name</label>
                        <input 
                            type="text" 
                            className="input-field" 
                            value={formData.name}
                            onChange={e => setFormData({...formData, name: e.target.value})}
                            placeholder="e.g. Sugar"
                            required
                        />
                    </div>
                    
                    <div className="input-group">
                        <label className="input-label">Price (₹)</label>
                        <input 
                            type="number" 
                            step="0.01"
                            className="input-field" 
                            value={formData.currentPrice || ''}
                            onChange={e => setFormData({...formData, currentPrice: parseFloat(e.target.value)})}
                            required
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
                                onChange={e => setFormData({...formData, packageQuantity: parseFloat(e.target.value)})}
                                min="0.01"
                            />
                        </div>
                        <div className="input-group">
                            <label className="input-label">Pack Unit</label>
                            <select 
                                className="input-field"
                                value={formData.packageUnit || ''}
                                onChange={e => setFormData({...formData, packageUnit: e.target.value})}
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

                    <div className="input-group" style={{ flexDirection: 'row', alignItems: 'center', gap: '0.5rem', marginTop: '1rem' }}>
                        <input 
                            type="checkbox" 
                            id="isActive"
                            checked={formData.active}
                            onChange={e => setFormData({...formData, active: e.target.checked})}
                        />
                        <label htmlFor="isActive" className="input-label" style={{ marginBottom: 0 }}>Variant is Active</label>
                    </div>
                </div>
                <div className="modal-footer">
                    <button type="button" className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
                    <button type="submit" className="btn btn-primary" disabled={loading}>
                        {loading ? <div className="spinner"></div> : 'Create Variant'}
                    </button>
                </div>
            </form>
        </Modal>
    );
};
