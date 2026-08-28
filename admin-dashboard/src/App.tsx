import ProductsPage from './pages/ProductsPage';
import './index.css';

function App() {
  return (
    <div className="app-container">
      <header className="topbar">
        <div className="brand">GreenGram</div>
        <div>
          <span className="badge badge-success">Admin</span>
        </div>
      </header>
      <main className="main-content">
        <ProductsPage />
      </main>
    </div>
  );
}

export default App;
