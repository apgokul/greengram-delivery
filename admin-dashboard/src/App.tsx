import { useState, useEffect } from 'react';
import ProductsPage from './pages/ProductsPage';
import { LoginPage } from './components/auth/LoginPage';
import './index.css';

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);

  useEffect(() => {
    const token = sessionStorage.getItem('adminToken');
    if (token) {
      setIsAuthenticated(true);
    }
  }, []);

  const handleLogout = () => {
    sessionStorage.removeItem('adminToken');
    setIsAuthenticated(false);
  };

  if (!isAuthenticated) {
    return <LoginPage onLoginSuccess={() => setIsAuthenticated(true)} />;
  }

  return (
    <div className="app-container">
      <header className="topbar">
        <div className="brand">GreenGram</div>
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
          <span className="badge badge-success">Admin</span>
          <button className="btn btn-outline" onClick={handleLogout} style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}>
            Logout
          </button>
        </div>
      </header>
      <main className="main-content">
        <ProductsPage />
      </main>
    </div>
  );
}

export default App;
