import React, { useState, useEffect } from 'react';
import { Modal } from '../ui/Modal';
import { Product } from '../../types/product';
import { productsApi } from '../../api/products';

interface AdjustStockModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess: () => void;
    product: Product | null;
}

export const AdjustStockModal: React.FC<AdjustStockModalProps> = ({ isOpen, onClose, onSuccess, product }) => {
    const [operation, setOperation] = useState<'ADD' | 'REDUCE'>('ADD');
    const [quantity, setQuantity] = useState<number>(0);
    const [reason, setReason] = useState<string>('');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (isOpen) {
            setOperation('ADD');
            setQuantity(0);
            setReason('');
            setError(null);
        }
    }, [isOpen]);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!product) return;
        setError(null);

        if (!quantity || isNaN(quantity) || quantity <= 0) {
            setError("Quantity must be greater than zero.");
            return;
        }

        if (!product.inventory) {
            setError("Inventory data is missing.");
            return;
        }

        const delta = operation === 'ADD' ? quantity : -quantity;

        setLoading(true);
        try {
            await productsApi.adjustStock(product.inventory.id, delta, reason.trim() || undefined);
            onSuccess();
            onClose();
        } catch (err: any) {
            setError(err.message || 'Failed to adjust stock');
        } finally {
            setLoading(false);
        }
    };

    if (!product) return null;

    return (
        <Modal isOpen={isOpen} onClose={onClose} title={`Adjust Stock: ${product.name}`}>
            <form onSubmit={handleSubmit}>
                <div className="modal-body">
                    {error && <div className="alert alert-error">{error}</div>}

                    <div className="input-group">
                        <label className="input-label">Current Stock</label>
                        <div className="input-field" style={{ backgroundColor: 'rgba(0,0,0,0.2)' }}>
                            {product.inventory?.stockQuantity ?? 0} {product.inventory?.unit}
                        </div>
                    </div>

                    <div className="input-group">
                        <label className="input-label">Operation</label>
                        <select
                            className="input-field"
                            value={operation}
                            onChange={(e) => setOperation(e.target.value as 'ADD' | 'REDUCE')}
                        >
                            <option value="ADD">Add Stock</option>
                            <option value="REDUCE">Reduce Stock</option>
                        </select>
                    </div>

                    <div className="input-group">
                        <label className="input-label">Quantity (Kg/L)</label>
                        <input
                            type="number"
                            step="0.01"
                            className="input-field"
                            value={quantity || ''}
                            onChange={e => setQuantity(parseFloat(e.target.value))}
                            min="0.01"
                            required
                        />
                    </div>

                    <div className="input-group">
                        <label className="input-label">Reason (optional)</label>
                        <input
                            type="text"
                            className="input-field"
                            value={reason}
                            onChange={e => setReason(e.target.value)}
                            placeholder="e.g. Restock delivery"
                        />
                    </div>
                </div>
                <div className="modal-footer">
                    <button type="button" className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
                    <button type="submit" className="btn btn-primary" disabled={loading}>
                        {loading ? <div className="spinner"></div> : 'Confirm Adjustment'}
                    </button>
                </div>
            </form>
        </Modal>
    );
};
