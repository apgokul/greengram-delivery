

export const authApi = {
    login: async (credentials: any) => {
        const response = await fetch(`${import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'}/api/admin/auth/login`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(credentials),
        });

        if (!response.ok) {
            let errorMsg = 'Invalid credentials';
            try {
                const data = await response.json();
                errorMsg = data.error || errorMsg;
            } catch (e) {}
            throw new Error(errorMsg);
        }

        return await response.json();
    }
};
