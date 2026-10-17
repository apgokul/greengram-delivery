import { useState, useEffect } from 'react';
import ProductsPage from './pages/ProductsPage';
import ChatMonitor from './pages/ChatMonitor';
import { LoginPage } from './components/auth/LoginPage';
import './index.css';

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);
  const [activeTab, setActiveTab] = useState<'products' | 'chats'>('products');

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
        <div style={{ display: 'flex', alignItems: 'center', gap: '2rem' }}>
          <div className="brand">GreenGram</div>
          <nav className="nav-tabs">
            <button
              className={`nav-tab-btn ${activeTab === 'products' ? 'active' : ''}`}
              onClick={() => setActiveTab('products')}
            >
              📦 Products
            </button>
            <button
              className={`nav-tab-btn ${activeTab === 'chats' ? 'active' : ''}`}
              onClick={() => setActiveTab('chats')}
            >
              💬 Chat Monitor
            </button>
          </nav>
        </div>
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
          <span className="badge badge-success">Admin</span>
          <button className="btn btn-outline" onClick={handleLogout} style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}>
            Logout
          </button>
        </div>
      </header>
      <main className="main-content">
        {activeTab === 'products' ? <ProductsPage /> : <ChatMonitor />}
      </main>
    </div>
  );
}

export default App;
