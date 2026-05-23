import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './index.css'

// Claude 风格：仅亮色，避免任何 dark class 残留导致页面深浅不一致
(function initTheme() {
    document.documentElement.classList.remove('dark');
    localStorage.setItem('theme', 'light');
})();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
