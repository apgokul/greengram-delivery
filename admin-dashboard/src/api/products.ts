import { apiClient } from './client';
import { Product, CreateProductRequest, ApiResponse } from '../types/product';

export const productsApi = {
    getAll: () => {
        return apiClient.get<Product[]>('/api/admin/products');
    },

    create: (data: CreateProductRequest) => {
        return apiClient.post<Product>('/api/admin/products', data);
    },

    updatePrice: (sku: string, newPrice: number) => {
        return apiClient.patch<ApiResponse<any>>(`/api/admin/products/${sku}/price?newPrice=${newPrice}`);
    },

    adjustStock: (sku: string, adjustmentAmount: number, reason: string) => {
        return apiClient.patch<ApiResponse<any>>(`/api/admin/products/${sku}/stock?adjustmentAmount=${adjustmentAmount}&reason=${encodeURIComponent(reason)}`);
    },

    toggleActive: (sku: string, isActive: boolean) => {
        return apiClient.patch<ApiResponse<any>>(`/api/admin/products/${sku}/toggle-active?isActive=${isActive}`);
    }
};
