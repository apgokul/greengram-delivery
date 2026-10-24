import { useState, useEffect } from 'react';
import { ProductTable } from '../components/products/ProductTable';
import { CreateProductModal } from '../components/products/CreateProductModal';
import { AdjustStockModal } from '../components/products/AdjustStockModal';
import { EditPriceModal } from '../components/products/EditPriceModal';
import { productsApi } from '../api/products';
import { Product } from '../types/product';

export default function ProductsPage() {
    const [products, setProducts] = useState<Product[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // Modal states
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    
    const [isAdjustStockOpen, setIsAdjustStockOpen] = useState(false);
    const [selectedForStock, setSelectedForStock] = useState<Product | null>(null);
    
    const [isEditPriceOpen, setIsEditPriceOpen] = useState(false);
    const [selectedForPrice, setSelectedForPrice] = useState<Product | null>(null);

    const fetchProducts = async () => {
        setLoading(true);
        setError(null);
        try {
            const data = await productsApi.getAll();
            setProducts(data);
        } catch (err: any) {
            setError(err.message || 'Failed to load products');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchProducts();
    }, []);

    const handleToggleActive = async (product: Product) => {
        if (!window.confirm(`Are you sure you want to ${product.active ? 'deactivate' : 'activate'} ${product.name}?`)) {
            return;
        }

        try {
            await productsApi.toggleActive(product.sku, !product.active);
            fetchProducts();
        } catch (err: any) {
            alert(err.message || 'Failed to toggle status');
        }
    };

    const openAdjustStock = (product: Product) => {
        setSelectedForStock(product);
        setIsAdjustStockOpen(true);
    };

    const openEditPrice = (product: Product) => {
        setSelectedForPrice(product);
        setIsEditPriceOpen(true);
    };

    return (
        <div>
            <div className="products-header">
                <div>
                    <h1>Products</h1>
                    <p style={{ color: 'var(--text-secondary)' }}>Manage your inventory, pricing, and product status.</p>
                </div>
                <div className="products-header-actions">
                    <button className="btn btn-secondary" onClick={fetchProducts}>
                        Refresh
                    </button>
                    <button className="btn btn-primary" onClick={() => setIsCreateOpen(true)}>
                        + Add Product
                    </button>
                </div>
            </div>

            <ProductTable 
                products={products}
                loading={loading}
                error={error}
                onEditPrice={openEditPrice}
                onAdjustStock={openAdjustStock}
                onToggleActive={handleToggleActive}
            />

            <CreateProductModal 
                isOpen={isCreateOpen}
                onClose={() => setIsCreateOpen(false)}
                onSuccess={fetchProducts}
            />

            <AdjustStockModal
                isOpen={isAdjustStockOpen}
                onClose={() => setIsAdjustStockOpen(false)}
                onSuccess={fetchProducts}
                product={selectedForStock}
            />

            <EditPriceModal
                isOpen={isEditPriceOpen}
                onClose={() => setIsEditPriceOpen(false)}
                onSuccess={fetchProducts}
                product={selectedForPrice}
            />
        </div>
    );
}
