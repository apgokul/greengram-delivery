export interface Product {
    sku: string;
    name: string;
    currentPrice: number;
    stockQuantity: number;
    quantity?: number;
    unit?: string;
    active: boolean;
}

export interface CreateProductRequest {
    sku: string;
    name: string;
    currentPrice: number;
    stockQuantity: number;
    quantity?: number;
    unit?: string;
    active: boolean;
}

export interface StockAdjustmentRequest {
    adjustmentAmount: number;
    reason: string;
}

export interface ApiResponse<T> {
    success?: boolean;
    error?: string;
    message?: string;
    data?: T;
}
