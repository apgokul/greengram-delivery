import React, { useState } from 'react';
import { Modal } from '../ui/Modal';
import { CreateProductRequest } from '../../types/product';
import { productsApi } from '../../api/products';

interface CreateProductModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess: () => void;
}

export const CreateProductModal: React.FC<CreateProductModalProps> = ({ isOpen, onClose, onSuccess }) => {
    const [formData, setFormData] = useState<CreateProductRequest>({
        sku: '',
        name: '',
        currentPrice: 0,
        packageQuantity: 1,
        packageUnit: 'KG',
        active: true
    });
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);

        if (!formData.sku || !formData.name || formData.currentPrice <= 0) {
            setError("Please fill out all required fields with valid values.");
            return;
        }

        setLoading(true);
        try {
            await productsApi.create({
                ...formData,
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
        <Modal isOpen={isOpen} onClose={onClose} title="Add New Product">
            <form onSubmit={handleSubmit}>
                <div className="modal-body">
                    {error && <div className="alert alert-error">{error}</div>}
                    
                    <div className="input-group">
                        <label className="input-label">SKU</label>
                        <input 
                            type="text" 
                            className="input-field" 
                            value={formData.sku}
                            onChange={e => setFormData({...formData, sku: e.target.value})}
                            placeholder="e.g. CHILLIPWD_1KG"
                            required
                        />
                    </div>
                    
                    <div className="input-group">
                        <label className="input-label">Product Name</label>
                        <input 
                            type="text" 
                            className="input-field" 
                            value={formData.name}
                            onChange={e => setFormData({...formData, name: e.target.value})}
                            placeholder="e.g. Chilli Powder"
                            required
                        />
                    </div>
                    
                    <div className="input-group">
                        <label className="input-label">Initial Price (₹)</label>
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
                            <label className="input-label">Quantity / Weight</label>
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
                            <label className="input-label">Unit</label>
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



                    <div className="input-group" style={{ flexDirection: 'row', alignItems: 'center', gap: '0.5rem' }}>
                        <input 
                            type="checkbox" 
                            id="isActive"
                            checked={formData.active}
                            onChange={e => setFormData({...formData, active: e.target.checked})}
                        />
                        <label htmlFor="isActive" className="input-label" style={{ marginBottom: 0 }}>Product is Active</label>
                    </div>
                </div>
                <div className="modal-footer">
                    <button type="button" className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
                    <button type="submit" className="btn btn-primary" disabled={loading}>
                        {loading ? <div className="spinner"></div> : 'Create Product'}
                    </button>
                </div>
            </form>
        </Modal>
    );
};
