export interface InventoryDTO {
    id: number;
    baseSku: string;
    stockQuantity: number;
    unit: 'G' | 'KG' | 'ML' | 'L' | string;
}

export interface Product {
    id?: number;
    sku: string;
    name: string;
    currentPrice: number;
    packageQuantity?: number;
    packageUnit?: 'G' | 'KG' | 'ML' | 'L' | string;
    active: boolean;
    inventory?: InventoryDTO;
}

export interface CreateProductRequest {
    sku: string;
    name: string;
    currentPrice: number;
    packageQuantity?: number;
    packageUnit?: 'G' | 'KG' | 'ML' | 'L' | string;
    active: boolean;
}


export interface ApiResponse<T> {
    success?: boolean;
    error?: string;
    message?: string;
    data?: T;
}
