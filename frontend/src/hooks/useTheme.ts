import {useEffect} from 'react';

type Theme = 'light';

export function useTheme() {
    useEffect(() => {
        document.documentElement.classList.remove('dark');
        localStorage.setItem('theme', 'light');
    }, []);

    const toggleTheme = () => {
        // Claude 风格仅亮色，保留 API 兼容性
    };

    return {theme: 'light' as Theme, toggleTheme};
}
