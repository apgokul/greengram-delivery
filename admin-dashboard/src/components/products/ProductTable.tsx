import React, { useState, useMemo } from 'react';
import { Product } from '../../types/product';

type SortKey = 'name' | 'sku' | 'packageQuantity' | 'currentPrice' | 'stockQuantity' | 'active';

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
            let valA: any = (a as any)[sortKey];
            let valB: any = (b as any)[sortKey];

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

            // Handle nested objects and mapped keys
            if (sortKey === 'stockQuantity') {
                valA = a.inventory?.stockQuantity || 0;
                valB = b.inventory?.stockQuantity || 0;
            } else if (sortKey === 'packageQuantity') {
                valA = a.packageQuantity || 0;
                valB = b.packageQuantity || 0;
            }

            let result = 0;
            if (valA < valB) result = -1;
            if (valA > valB) result = 1;
            
            // If primary sort is tied and we're sorting by name, sort by quantity ascending
            if (result === 0 && sortKey === 'name') {
                const qA = a.packageQuantity || 0;
                const qB = b.packageQuantity || 0;
                return qA - qB;
            }
            
            return sortDir === 'asc' ? result : -result;
        });

        return sorted;
    }, [products, sortKey, sortDir]);

    const groupedProducts = useMemo(() => {
        const groups = new Map<number, Product[]>();
        
        sortedProducts.forEach(product => {
            const inventoryId = product.inventory?.id ?? -1;
            if (!groups.has(inventoryId)) {
                groups.set(inventoryId, []);
            }
            groups.get(inventoryId)!.push(product);
        });

        return Array.from(groups.values());
    }, [sortedProducts]);

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
                        <th onClick={() => handleSort('name')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            PRODUCT {sortKey === 'name' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th onClick={() => handleSort('stockQuantity')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            STOCK {sortKey === 'stockQuantity' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th style={{ textAlign: 'center' }}>INVENTORY ACTIONS</th>
                        <th onClick={() => handleSort('sku')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            SKU {sortKey === 'sku' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th onClick={() => handleSort('packageQuantity')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            PACK SIZE {sortKey === 'packageQuantity' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th onClick={() => handleSort('currentPrice')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            PRICE (₹) {sortKey === 'currentPrice' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th onClick={() => handleSort('active')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            STATUS {sortKey === 'active' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th style={{ textAlign: 'right' }}>VARIANT ACTIONS</th>
                    </tr>
                </thead>
                <tbody>
                    {groupedProducts.map((group, groupIndex) => {
                        const parent = group[0];
                        const inventory = parent.inventory;
                        const hasInventory = !!inventory;
                        const rowSpan = group.length;
                        
                        return (
                            <React.Fragment key={`group-${inventory?.id || groupIndex}`}>
                                {group.map((product, productIndex) => {
                                    const isLastChild = productIndex === group.length - 1;
                                    return (
                                        <tr 
                                            key={product.sku}
                                            style={{ borderBottom: isLastChild ? '1px solid var(--border-color)' : 'none' }}
                                        >
                                            {productIndex === 0 && (
                                                <>
                                                    <td rowSpan={rowSpan} style={{ fontWeight: 'bold', verticalAlign: 'middle' }}>
                                                        {parent.name}
                                                    </td>
                                                    <td rowSpan={rowSpan} style={{ verticalAlign: 'middle' }}>
                                                        <span style={{ 
                                                            fontWeight: 600,
                                                            color: (inventory?.stockQuantity ?? 0) === 0 ? 'var(--danger-color)' : 'inherit'
                                                        }}>
                                                            {inventory?.stockQuantity ?? 0} {inventory?.unit ?? ''}
                                                        </span>
                                                    </td>
                                                    <td rowSpan={rowSpan} style={{ textAlign: 'center', verticalAlign: 'middle' }}>
                                                        {hasInventory && (
                                                            <button 
                                                                className="btn btn-secondary btn-sm"
                                                                onClick={() => onAdjustStock(parent)}
                                                            >
                                                                Adjust Stock
                                                            </button>
                                                        )}
                                                    </td>
                                                </>
                                            )}
                                            
                                            <td style={{ fontFamily: 'monospace', color: 'var(--text-secondary)', verticalAlign: 'middle' }}>
                                                {product.sku}
                                            </td>
                                            <td style={{ color: 'var(--text-secondary)', verticalAlign: 'middle' }}>
                                                {product.packageQuantity && product.packageUnit ? (
                                                    <span>
                                                        {Number(product.packageQuantity)} {product.packageUnit === 'L' ? 'L' : product.packageUnit.toLowerCase()}
                                                    </span>
                                                ) : (
                                                    <span style={{ opacity: 0.5 }}>-</span>
                                                )}
                                            </td>
                                            <td style={{ verticalAlign: 'middle' }}>{product.currentPrice.toFixed(2)}</td>
                                            <td style={{ verticalAlign: 'middle' }}>
                                                <span className={`badge ${product.active ? 'badge-success' : 'badge-neutral'}`}>
                                                    {product.active ? 'Active' : 'Inactive'}
                                                </span>
                                            </td>
                                            <td style={{ textAlign: 'right', verticalAlign: 'middle' }}>
                                                <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end', alignItems: 'center' }}>
                                                    <button 
                                                        className="btn btn-secondary btn-sm"
                                                        onClick={() => onEditPrice(product)}
                                                    >
                                                        Edit Price
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
                                    );
                                })}
                            </React.Fragment>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
};
