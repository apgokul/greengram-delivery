import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

// Mirroring the pure logic functions from ChatMonitor.tsx
function displayIdentifier(id) {
    if (!id) return '';
    return /^\d+$/.test(id) ? `+${id}` : id;
}

function getUsableProfileName(name) {
    if (!name) return null;
    const trimmed = name.trim();
    if (!trimmed) return null;
    const lower = trimmed.toLowerCase();
    if (lower === 'valued customer' || lower === 'customer') {
        return null;
    }
    return trimmed;
}

function filterChats(chats, searchQuery) {
    const query = searchQuery.toLowerCase().trim();
    if (!query) return chats;
    return chats.filter((chat) => {
        const usableName = getUsableProfileName(chat.profileName);
        return (
            chat.phoneNumber.toLowerCase().includes(query) ||
            chat.lastMessage.toLowerCase().includes(query) ||
            (usableName ? usableName.toLowerCase().includes(query) : false)
        );
    });
}

function resolveLabels(chat) {
    const usableName = getUsableProfileName(chat.profileName);
    if (usableName) {
        return {
            primary: usableName,
            secondary: displayIdentifier(chat.phoneNumber),
            hasUsableName: true
        };
    }
    return {
        primary: displayIdentifier(chat.phoneNumber),
        secondary: null,
        hasUsableName: false
    };
}

describe('Chat Monitor Profile Name Logic', () => {

    describe('getUsableProfileName', () => {
        test('returns trimmed name for valid profile names', () => {
            assert.equal(getUsableProfileName('Anu Joseph'), 'Anu Joseph');
            assert.equal(getUsableProfileName('  Dr. Roy  '), 'Dr. Roy');
            assert.equal(getUsableProfileName('Gokul Prakash'), 'Gokul Prakash');
        });

        test('returns null for null, undefined, or empty strings', () => {
            assert.equal(getUsableProfileName(null), null);
            assert.equal(getUsableProfileName(undefined), null);
            assert.equal(getUsableProfileName(''), null);
            assert.equal(getUsableProfileName('   '), null);
        });

        test('returns null for placeholder names case-insensitively', () => {
            assert.equal(getUsableProfileName('Valued Customer'), null);
            assert.equal(getUsableProfileName('valued customer'), null);
            assert.equal(getUsableProfileName('VALUED CUSTOMER'), null);
            assert.equal(getUsableProfileName('Customer'), null);
            assert.equal(getUsableProfileName('customer'), null);
            assert.equal(getUsableProfileName('CUSTOMER'), null);
        });
    });

    describe('Label Resolution (Primary vs Secondary)', () => {
        test('when profile name is usable, name is primary and phone is secondary', () => {
            const chat = {
                phoneNumber: '919876543210',
                profileName: 'Anu Joseph',
                lastMessage: 'Order confirmed'
            };
            const labels = resolveLabels(chat);
            assert.equal(labels.primary, 'Anu Joseph');
            assert.equal(labels.secondary, '+919876543210');
            assert.equal(labels.hasUsableName, true);
        });

        test('when profile name is absent, phone is primary and secondary is null', () => {
            const chat = {
                phoneNumber: '919876543210',
                profileName: null,
                lastMessage: 'Where is my order?'
            };
            const labels = resolveLabels(chat);
            assert.equal(labels.primary, '+919876543210');
            assert.equal(labels.secondary, null);
            assert.equal(labels.hasUsableName, false);
        });

        test('when profile name is placeholder, falls back to phone as primary', () => {
            const chat = {
                phoneNumber: '919123456789',
                profileName: 'Valued Customer',
                lastMessage: 'Hi'
            };
            const labels = resolveLabels(chat);
            assert.equal(labels.primary, '+919123456789');
            assert.equal(labels.secondary, null);
            assert.equal(labels.hasUsableName, false);
        });
    });

    describe('Search Filtering by Name, Phone, and Snippet', () => {
        const testChats = [
            { phoneNumber: '919876543210', profileName: 'Anu Joseph', lastMessage: 'Organic rice inquiry' },
            { phoneNumber: '919123456789', profileName: null, lastMessage: 'Order 1042 status' },
            { phoneNumber: '919988776655', profileName: 'Valued Customer', lastMessage: 'Delivery update' },
            { phoneNumber: 'bsuid.user_99', profileName: 'Joseph Kurian', lastMessage: 'Hi bot' }
        ];

        test('matches chat by profile name query', () => {
            const results = filterChats(testChats, 'anu');
            assert.equal(results.length, 1);
            assert.equal(results[0].phoneNumber, '919876543210');
        });

        test('matches multiple chats by shared name token', () => {
            const results = filterChats(testChats, 'joseph');
            assert.equal(results.length, 2);
            const phones = results.map(c => c.phoneNumber);
            assert.ok(phones.includes('919876543210'));
            assert.ok(phones.includes('bsuid.user_99'));
        });

        test('matches by phone number digits', () => {
            const results = filterChats(testChats, '123456');
            assert.equal(results.length, 1);
            assert.equal(results[0].phoneNumber, '919123456789');
        });

        test('matches by last message text snippet', () => {
            const results = filterChats(testChats, 'delivery');
            assert.equal(results.length, 1);
            assert.equal(results[0].phoneNumber, '919988776655');
        });

        test('does not match placeholder name when searching for placeholder term', () => {
            const results = filterChats(testChats, 'valued customer');
            assert.equal(results.length, 0, 'Placeholder name must not match as a real profile name');
        });
    });
});
