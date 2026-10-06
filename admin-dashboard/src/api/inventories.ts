import { apiClient } from './client';
import { InventoryDTO } from '../types/product';

export const inventoryApi = {
    getAllInventories: async () => {
        return apiClient.get<InventoryDTO[]>('/api/admin/inventories');
    },

    createInventory: async (data: { baseSku: string; unit: string }) => {
        return apiClient.post<InventoryDTO>('/api/admin/inventories', data);
    }
};
