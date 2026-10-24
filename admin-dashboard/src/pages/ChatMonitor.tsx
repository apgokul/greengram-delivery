import { useState, useEffect, useRef } from 'react';
import { messagesApi } from '../api/messages';
import { ChatSummary, WhatsAppMessageLog } from '../types/message';

export default function ChatMonitor() {
    const [chats, setChats] = useState<ChatSummary[]>([]);
    const [loadingChats, setLoadingChats] = useState<boolean>(true);
    const [chatsError, setChatsError] = useState<string | null>(null);

    const [selectedPhone, setSelectedPhone] = useState<string | null>(null);
    const [showMobileChat, setShowMobileChat] = useState<boolean>(false);
    const [messages, setMessages] = useState<WhatsAppMessageLog[]>([]);
    const [loadingMessages, setLoadingMessages] = useState<boolean>(false);
    const [messagesError, setMessagesError] = useState<string | null>(null);

    const [searchQuery, setSearchQuery] = useState<string>('');

    const chatHistoryRef = useRef<HTMLDivElement>(null);
    const messagesEndRef = useRef<HTMLDivElement>(null);

    const displayIdentifier = (id: string): string => {
        if (!id) return '';
        return /^\d+$/.test(id) ? `+${id}` : id;
    };

    const fetchChats = async () => {
        setLoadingChats(true);
        setChatsError(null);
        try {
            const data = await messagesApi.getActiveChats();
            setChats(data);
        } catch (err: any) {
            setChatsError(err.message || 'Failed to load chat sessions');
        } finally {
            setLoadingChats(false);
        }
    };

    // Fix 3: Stale request protection with effect cleanup and immediate state reset
    useEffect(() => {
        if (!selectedPhone) {
            setMessages([]);
            setLoadingMessages(false);
            setMessagesError(null);
            return;
        }

        let isCurrent = true;
        // Immediately reset messages and status so previous customer's messages are never shown
        setMessages([]);
        setLoadingMessages(true);
        setMessagesError(null);

        messagesApi.getChatHistory(selectedPhone)
            .then((data) => {
                if (isCurrent) {
                    setMessages(data);
                    setMessagesError(null);
                }
            })
            .catch((err: any) => {
                if (isCurrent) {
                    setMessagesError(err.message || 'Failed to load conversation history');
                }
            })
            .finally(() => {
                if (isCurrent) {
                    setLoadingMessages(false);
                }
            });

        return () => {
            isCurrent = false;
        };
    }, [selectedPhone]);

    const refreshCurrentChat = async () => {
        if (!selectedPhone) return;
        const target = selectedPhone;
        setLoadingMessages(true);
        setMessagesError(null);
        try {
            const data = await messagesApi.getChatHistory(target);
            if (selectedPhone === target) {
                setMessages(data);
                setMessagesError(null);
            }
        } catch (err: any) {
            if (selectedPhone === target) {
                setMessagesError(err.message || 'Failed to load conversation history');
            }
        } finally {
            if (selectedPhone === target) {
                setLoadingMessages(false);
            }
        }
    };

    useEffect(() => {
        fetchChats();
    }, []);

    useEffect(() => {
        // Auto-scroll to bottom of chat history when messages or view updates
        if (chatHistoryRef.current) {
            chatHistoryRef.current.scrollTop = chatHistoryRef.current.scrollHeight;
        }
    }, [messages, showMobileChat]);

    const handleSelectChat = (phoneNumber: string) => {
        if (selectedPhone !== phoneNumber) {
            setMessages([]);
            setSelectedPhone(phoneNumber);
        }
        setShowMobileChat(true);
    };

    const filteredChats = chats.filter((chat) => {
        const query = searchQuery.toLowerCase().trim();
        if (!query) return true;
        return (
            chat.phoneNumber.toLowerCase().includes(query) ||
            chat.lastMessage.toLowerCase().includes(query)
        );
    });

    const formatTimestamp = (isoString: string): string => {
        if (!isoString) return '';
        try {
            const parts = isoString.split('T');
            if (parts.length === 2) {
                const date = parts[0];
                const time = parts[1].substring(0, 5);
                return `${date} ${time}`;
            }
            return isoString;
        } catch {
            return isoString;
        }
    };

    const formatSnippetTime = (isoString: string): string => {
        if (!isoString) return '';
        try {
            const parts = isoString.split('T');
            if (parts.length === 2) {
                const [_, m, d] = parts[0].split('-');
                const time = parts[1].substring(0, 5);
                return `${d}/${m} ${time}`;
            }
            return isoString;
        } catch {
            return isoString;
        }
    };

    return (
        <div className={`chat-monitor-layout ${showMobileChat ? 'mobile-show-chat' : 'mobile-show-list'}`}>
            {/* LEFT SIDEBAR: Active Chats List */}
            <aside className="chat-sidebar">
                <div className="chat-sidebar-header">
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <h2>Chats ({chats.length})</h2>
                        <button
                            className="btn btn-secondary btn-sm chat-refresh-btn"
                            onClick={fetchChats}
                            disabled={loadingChats}
                            title="Refresh chat sessions"
                        >
                            {loadingChats ? 'Refreshing...' : 'Refresh'}
                        </button>
                    </div>
                    <div style={{ marginTop: '0.75rem' }}>
                        <input
                            type="text"
                            className="search-input"
                            placeholder="Search by phone or snippet..."
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                        />
                    </div>
                </div>

                <div className="chat-list-scroll">
                    {loadingChats && chats.length === 0 && (
                        <div className="chat-status-message">
                            <div className="spinner" style={{ margin: '1rem auto' }}></div>
                            <p>Loading active chats...</p>
                        </div>
                    )}

                    {chatsError && (
                        <div className="alert-error" style={{ margin: '1rem' }}>
                            <p>{chatsError}</p>
                            <button className="btn btn-secondary" onClick={fetchChats} style={{ marginTop: '0.5rem' }}>
                                Retry
                            </button>
                        </div>
                    )}

                    {!loadingChats && !chatsError && filteredChats.length === 0 && (
                        <div className="chat-status-message">
                            <p>{searchQuery ? 'No chats match your search.' : 'No active customer chats recorded yet.'}</p>
                        </div>
                    )}

                    {filteredChats.map((chat) => {
                        const isSelected = selectedPhone === chat.phoneNumber;
                        return (
                            <div
                                key={chat.phoneNumber}
                                role="button"
                                tabIndex={0}
                                aria-pressed={isSelected}
                                className={`chat-item ${isSelected ? 'active' : ''}`}
                                onClick={() => handleSelectChat(chat.phoneNumber)}
                                onKeyDown={(e) => {
                                    if (e.key === 'Enter' || e.key === ' ') {
                                        e.preventDefault();
                                        handleSelectChat(chat.phoneNumber);
                                    }
                                }}
                            >
                                <div className="chat-item-header">
                                    <span className="chat-item-phone">{displayIdentifier(chat.phoneNumber)}</span>
                                    <span className="chat-item-time">{formatSnippetTime(chat.lastMessageTimestamp)}</span>
                                </div>
                                {chat.awaitingLocation && (
                                    <div style={{ marginTop: '0.25rem', marginBottom: '0.25rem' }}>
                                        <span style={{
                                            backgroundColor: 'var(--warning-bg)',
                                            color: 'var(--warning-color)',
                                            border: '1px solid var(--warning-border)',
                                            fontSize: '0.72rem',
                                            fontWeight: 600,
                                            padding: '0.15rem 0.45rem',
                                            borderRadius: '4px',
                                            display: 'inline-flex',
                                            alignItems: 'center',
                                            gap: '0.25rem'
                                        }}>
                                            📍 Awaiting Location
                                        </span>
                                    </div>
                                )}
                                <div className="chat-item-snippet">
                                    <span className="snippet-direction">
                                        {chat.lastMessageDirection === 'OUTBOUND' ? 'Bot: ' : 'Customer: '}
                                    </span>
                                    {chat.lastMessage}
                                </div>
                            </div>
                        );
                    })}
                </div>
            </aside>

            {/* RIGHT PANEL: Chronological Conversation */}
            <main className="chat-main-panel">
                {selectedPhone ? (
                    <>
                        <div className="chat-header">
                            <div className="chat-header-info">
                                <button
                                    type="button"
                                    className="btn btn-secondary btn-sm chat-back-btn"
                                    onClick={() => setShowMobileChat(false)}
                                    aria-label="Back to chat list"
                                >
                                    ← Chats
                                </button>
                                <div className="chat-header-title-group">
                                    <h3 style={{ margin: 0 }}>{displayIdentifier(selectedPhone)}</h3>
                                    <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                                        {messages.length} message{messages.length === 1 ? '' : 's'} recorded
                                    </span>
                                </div>
                            </div>
                            <button
                                className="btn btn-secondary btn-sm chat-refresh-btn"
                                onClick={refreshCurrentChat}
                                disabled={loadingMessages}
                            >
                                {loadingMessages ? 'Updating...' : 'Refresh Chat'}
                            </button>
                        </div>

                        <div className="chat-history-scroll" ref={chatHistoryRef}>
                            {loadingMessages && messages.length === 0 && (
                                <div className="chat-status-message">
                                    <div className="spinner" style={{ margin: '2rem auto' }}></div>
                                    <p>Loading messages...</p>
                                </div>
                            )}

                            {messagesError && (
                                <div className="alert-error" style={{ margin: '2rem' }}>
                                    <p>{messagesError}</p>
                                    <button
                                        className="btn btn-secondary"
                                        onClick={refreshCurrentChat}
                                        style={{ marginTop: '0.5rem' }}
                                    >
                                        Retry
                                    </button>
                                </div>
                            )}

                            {!loadingMessages && !messagesError && messages.length === 0 && (
                                <div className="chat-status-message">
                                    <p>No messages recorded for this customer.</p>
                                </div>
                            )}

                            {messages.map((msg) => {
                                const isInbound = msg.direction === 'INBOUND';
                                return (
                                    <div
                                        key={msg.id}
                                        className={`message-row ${isInbound ? 'inbound-row' : 'outbound-row'}`}
                                    >
                                        <div className={`message-bubble ${isInbound ? 'bubble-inbound' : 'bubble-outbound'}`}>
                                            <div className="bubble-sender">
                                                {isInbound ? 'Customer' : 'GreenGram Bot'}
                                            </div>
                                            <div className="bubble-text">{msg.messageBody}</div>
                                            <div className="bubble-meta">
                                                <span>{formatTimestamp(msg.timestamp)}</span>
                                                {msg.externalMessageId && (
                                                    <span className="bubble-id" title={`Meta Message ID: ${msg.externalMessageId}`}>
                                                        ✓✓
                                                    </span>
                                                )}
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}
                            <div ref={messagesEndRef} />
                        </div>
                    </>
                ) : (
                    <div className="chat-empty-selection">
                        <div style={{ textAlign: 'center', maxWidth: '400px' }}>
                            <div style={{ fontSize: '3rem', marginBottom: '1rem' }}>💬</div>
                            <h2>WhatsApp Chat Monitor</h2>
                            <p style={{ color: 'var(--text-secondary)', marginTop: '0.5rem' }}>
                                Select a customer conversation from the left sidebar to inspect chronological inbound and outbound message history.
                            </p>
                        </div>
                    </div>
                )}
            </main>
        </div>
    );
}
