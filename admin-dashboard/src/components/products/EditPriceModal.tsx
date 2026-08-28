import React, { useState, useEffect } from 'react';
import { Modal } from '../ui/Modal';
import { Product } from '../../types/product';
import { productsApi } from '../../api/products';

interface EditPriceModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess: () => void;
    product: Product | null;
}

export const EditPriceModal: React.FC<EditPriceModalProps> = ({ isOpen, onClose, onSuccess, product }) => {
    const [price, setPrice] = useState<number>(0);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (isOpen && product) {
            setPrice(product.currentPrice);
            setError(null);
        }
    }, [isOpen, product]);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!product) return;
        setError(null);

        if (price <= 0) {
            setError("Price must be greater than zero.");
            return;
        }

        setLoading(true);
        try {
            await productsApi.updatePrice(product.sku, price);
            onSuccess();
            onClose();
        } catch (err: any) {
            setError(err.message || 'Failed to update price');
        } finally {
            setLoading(false);
        }
    };

    if (!product) return null;

    return (
        <Modal isOpen={isOpen} onClose={onClose} title={`Edit Price: ${product.name}`}>
            <form onSubmit={handleSubmit}>
                <div className="modal-body">
                    {error && <div className="alert alert-error">{error}</div>}
                    
                    <div className="input-group">
                        <label className="input-label">Current Price (₹)</label>
                        <div className="input-field" style={{ backgroundColor: 'rgba(0,0,0,0.2)' }}>
                            {product.currentPrice.toFixed(2)}
                        </div>
                    </div>
                    
                    <div className="input-group">
                        <label className="input-label">New Price (₹)</label>
                        <input 
                            type="number" 
                            step="0.01"
                            className="input-field" 
                            value={price || ''}
                            onChange={e => setPrice(parseFloat(e.target.value))}
                            min="0.01"
                            required
                        />
                    </div>
                </div>
                <div className="modal-footer">
                    <button type="button" className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
                    <button type="submit" className="btn btn-primary" disabled={loading}>
                        {loading ? <div className="spinner"></div> : 'Update Price'}
                    </button>
                </div>
            </form>
        </Modal>
    );
};
