import { useState, useMemo } from 'react';
import { Product } from '../../types/product';

type SortKey = 'name' | 'sku' | 'quantity' | 'currentPrice' | 'stockQuantity' | 'active';

interface ProductTableProps {
    products: Product[];
    loading: boolean;
    error: string | null;
    onEditPrice: (product: Product) => void;
    onAdjustStock: (product: Product) => void;
    onToggleActive: (product: Product) => void;
}

export const ProductTable = ({ 
    products, 
    loading, 
    error, 
    onEditPrice, 
    onAdjustStock,
    onToggleActive
}: ProductTableProps) => {
    const [sortKey, setSortKey] = useState<SortKey>('name');
    const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

    const handleSort = (key: SortKey) => {
        if (sortKey === key) {
            setSortDir(sortDir === 'asc' ? 'desc' : 'asc');
        } else {
            setSortKey(key);
            setSortDir('asc');
        }
    };

    const sortedProducts = useMemo(() => {
        const sorted = [...products].sort((a, b) => {
            let valA: any = a[sortKey];
            let valB: any = b[sortKey];

            // Handle string comparisons
            if (typeof valA === 'string' && typeof valB === 'string') {
                return sortDir === 'asc' 
                    ? valA.localeCompare(valB)
                    : valB.localeCompare(valA);
            }
            
            // Handle boolean comparisons
            if (typeof valA === 'boolean' && typeof valB === 'boolean') {
                const numA = valA ? 1 : 0;
                const numB = valB ? 1 : 0;
                return sortDir === 'asc' ? numB - numA : numA - numB;
            }

            // Handle undefined quantity gracefully (treat undefined as 0)
            if (sortKey === 'quantity') {
                valA = a.quantity || 0;
                valB = b.quantity || 0;
            }

            let result = 0;
            if (valA < valB) result = -1;
            if (valA > valB) result = 1;
            
            // If primary sort is tied and we're sorting by name, sort by quantity ascending
            if (result === 0 && sortKey === 'name') {
                const qA = a.quantity || 0;
                const qB = b.quantity || 0;
                return qA - qB;
            }
            
            return sortDir === 'asc' ? result : -result;
        });

        return sorted;
    }, [products, sortKey, sortDir]);

    if (loading) {
        return (
            <div className="table-container" style={{ padding: '3rem', textAlign: 'center' }}>
                <div className="spinner" style={{ margin: '0 auto', borderColor: 'rgba(59, 130, 246, 0.3)', borderTopColor: '#3b82f6' }}></div>
                <p style={{ marginTop: '1rem', color: 'var(--text-secondary)' }}>Loading products...</p>
            </div>
        );
    }

    if (error) {
        return (
            <div className="table-container" style={{ padding: '3rem', textAlign: 'center' }}>
                <div className="alert alert-error" style={{ display: 'inline-block', marginBottom: 0 }}>
                    {error}
                </div>
            </div>
        );
    }

    if (products.length === 0) {
        return (
            <div className="table-container" style={{ padding: '3rem', textAlign: 'center' }}>
                <p style={{ color: 'var(--text-secondary)' }}>No products found. Add a product to get started.</p>
            </div>
        );
    }

    return (
        <div className="table-container">
            <table className="data-table">
                <thead>
                    <tr>
                        <th style={{ width: '60px' }}>Sl. No.</th>
                        <th onClick={() => handleSort('name')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Product {sortKey === 'name' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th onClick={() => handleSort('sku')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            SKU {sortKey === 'sku' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th onClick={() => handleSort('quantity')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Pack Size {sortKey === 'quantity' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th onClick={() => handleSort('currentPrice')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Price (₹) {sortKey === 'currentPrice' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th onClick={() => handleSort('stockQuantity')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Stock {sortKey === 'stockQuantity' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th onClick={() => handleSort('active')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Status {sortKey === 'active' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th style={{ textAlign: 'right' }}>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    {sortedProducts.map((product, index) => (
                        <tr key={product.sku}>
                            <td style={{ color: 'var(--text-secondary)' }}>{index + 1}</td>
                            <td style={{ fontWeight: 500 }}>{product.name}</td>
                            <td style={{ fontFamily: 'monospace', color: 'var(--text-secondary)' }}>{product.sku}</td>
                            <td>
                                {product.quantity && product.unit ? (
                                    <span style={{ color: 'var(--text-secondary)' }}>
                                        {Number(product.quantity)} {product.unit === 'L' ? 'L' : product.unit.toLowerCase()}
                                    </span>
                                ) : (
                                    <span style={{ color: 'var(--text-secondary)', opacity: 0.5 }}>-</span>
                                )}
                            </td>
                            <td>{product.currentPrice.toFixed(2)}</td>
                            <td>
                                <span style={{ 
                                    fontWeight: 600,
                                    color: product.stockQuantity === 0 ? 'var(--danger-color)' : 'inherit'
                                }}>
                                    {product.stockQuantity}
                                </span>
                            </td>
                            <td>
                                <span className={`badge ${product.active ? 'badge-success' : 'badge-neutral'}`}>
                                    {product.active ? 'Active' : 'Inactive'}
                                </span>
                            </td>
                            <td style={{ textAlign: 'right' }}>
                                <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                                    <button 
                                        className="btn btn-secondary btn-sm"
                                        onClick={() => onEditPrice(product)}
                                    >
                                        Edit Price
                                    </button>
                                    <button 
                                        className="btn btn-secondary btn-sm"
                                        onClick={() => onAdjustStock(product)}
                                    >
                                        Adjust Stock
                                    </button>
                                    <button 
                                        className={`btn btn-sm ${product.active ? 'btn-danger' : 'btn-primary'}`}
                                        onClick={() => onToggleActive(product)}
                                    >
                                        {product.active ? 'Deactivate' : 'Activate'}
                                    </button>
                                </div>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
};
