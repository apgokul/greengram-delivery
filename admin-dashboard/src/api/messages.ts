import { apiClient } from './client';
import { ChatSummary, WhatsAppMessageLog } from '../types/message';

export const messagesApi = {
    getActiveChats: () => {
        return apiClient.get<ChatSummary[]>('/api/admin/messages/chats');
    },

    getChatHistory: (phoneNumber: string) => {
        return apiClient.get<WhatsAppMessageLog[]>(`/api/admin/messages/${encodeURIComponent(phoneNumber)}`);
    }
};
