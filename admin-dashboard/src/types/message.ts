export type MessageDirection = 'INBOUND' | 'OUTBOUND';

export interface ChatSummary {
    phoneNumber: string;
    lastMessage: string;
    lastMessageTimestamp: string;
    lastMessageDirection: MessageDirection;
    awaitingLocation?: boolean;
}

export interface WhatsAppMessageLog {
    id: number;
    externalMessageId?: string | null;
    phoneNumber: string;
    direction: MessageDirection;
    messageBody: string;
    timestamp: string;
}
