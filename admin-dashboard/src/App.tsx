import { useState, useEffect } from 'react';
import ProductsPage from './pages/ProductsPage';
import OrdersPage from './pages/OrdersPage';
import ChatMonitor from './pages/ChatMonitor';
import { LoginPage } from './components/auth/LoginPage';
import './index.css';

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);
  const [activeTab, setActiveTab] = useState<'products' | 'orders' | 'chats'>('products');

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
        <div className="topbar-brand-group">
          <div className="brand">GreenGram</div>
        </div>
        <nav className="nav-tabs" aria-label="Main Navigation">
          <button
            className={`nav-tab-btn ${activeTab === 'products' ? 'active' : ''}`}
            aria-current={activeTab === 'products' ? 'page' : undefined}
            onClick={() => setActiveTab('products')}
          >
            📦 Products
          </button>
          <button
            className={`nav-tab-btn ${activeTab === 'orders' ? 'active' : ''}`}
            aria-current={activeTab === 'orders' ? 'page' : undefined}
            onClick={() => setActiveTab('orders')}
          >
            🛍️ Orders
          </button>
          <button
            className={`nav-tab-btn ${activeTab === 'chats' ? 'active' : ''}`}
            aria-current={activeTab === 'chats' ? 'page' : undefined}
            onClick={() => setActiveTab('chats')}
          >
            💬 Chat Monitor
          </button>
        </nav>
        <div className="topbar-actions">
          <span className="badge badge-success">Admin</span>
          <button className="btn btn-outline btn-sm" onClick={handleLogout}>
            Logout
          </button>
        </div>
      </header>
      <main className="main-content">
        {activeTab === 'products' && <ProductsPage />}
        {activeTab === 'orders' && <OrdersPage />}
        {activeTab === 'chats' && <ChatMonitor />}
      </main>
    </div>
  );
}

export default App;
