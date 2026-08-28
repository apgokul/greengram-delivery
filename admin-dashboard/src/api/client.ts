const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
const API_KEY = import.meta.env.VITE_ADMIN_API_KEY || '';

export const apiClient = {
    async get<T>(path: string): Promise<T> {
        const response = await fetch(`${BASE_URL}${path}`, {
            method: 'GET',
            headers: {
                'X-Admin-API-Key': API_KEY,
                'Content-Type': 'application/json',
            },
        });
        return handleResponse<T>(response);
    },

    async post<T>(path: string, body: any): Promise<T> {
        const response = await fetch(`${BASE_URL}${path}`, {
            method: 'POST',
            headers: {
                'X-Admin-API-Key': API_KEY,
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(body),
        });
        return handleResponse<T>(response);
    },

    async patch<T>(path: string): Promise<T> {
        const response = await fetch(`${BASE_URL}${path}`, {
            method: 'PATCH',
            headers: {
                'X-Admin-API-Key': API_KEY,
                'Content-Type': 'application/json',
            },
        });
        return handleResponse<T>(response);
    }
};

async function handleResponse<T>(response: Response): Promise<T> {
    if (!response.ok) {
        let errorMessage = `HTTP Error ${response.status}`;
        try {
            const errorData = await response.json();
            errorMessage = errorData.error || errorData.message || errorMessage;
        } catch (e) {
            // response was not JSON
        }
        throw new Error(errorMessage);
    }
    
    // Some PATCH endpoints might return empty response, though ours seem to return JSON
    const text = await response.text();
    return text ? JSON.parse(text) : {} as T;
}
