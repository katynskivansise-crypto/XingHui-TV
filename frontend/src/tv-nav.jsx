import { useCallback, useEffect, useRef, useState } from 'react';

// ---- 简单的电视遥控器焦点导航（roving focus）----
// 焦点模型：把所有可聚焦项注册成一个二维网格，方向键移动，Enter 触发 onSelect。
// 这是 WebView 里做 Android TV 遥控器交互的最小方案（Tauri 前端沿用同一套）。

export function useTvGrid(rows, cols, opts = {}) {
  const { onSelect } = opts;
  const [r, setR] = useState(0);
  const [c, setC] = useState(0);
  const gridRef = useRef(null);

  const clamp = (v, max) => Math.max(0, Math.min(max - 1, v));

  useEffect(() => {
    const onKey = (e) => {
      switch (e.key) {
        case 'ArrowUp': setR((v) => clamp(v - 1, rows)); e.preventDefault(); break;
        case 'ArrowDown': setR((v) => clamp(v + 1, rows)); e.preventDefault(); break;
        case 'ArrowLeft': setC((v) => clamp(v - 1, cols)); e.preventDefault(); break;
        case 'ArrowRight': setC((v) => clamp(v + 1, cols)); e.preventDefault(); break;
        case 'Enter': onSelect && onSelect({ r, c }); e.preventDefault(); break;
        default: break;
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [rows, cols, r, c, onSelect]);

  const setFocus = useCallback((nr, nc) => { setR(nr); setC(nc); }, []);
  return { r, c, setFocus, gridRef };
}

// 焦点卡片：focused 时高亮放大（模拟遥控器选中态）
export function FocusCard({ focused, onClick, children, style }) {
  return (
    <div
      role="button"
      tabIndex={focused ? 0 : -1}
      onClick={onClick}
      style={{
        outline: focused ? '3px solid #ffd24d' : 'none',
        outlineOffset: focused ? '3px' : '0',
        transform: focused ? 'scale(1.06)' : 'scale(1)',
        transition: 'transform .12s ease, outline .12s ease',
        cursor: 'pointer',
        boxSizing: 'border-box',
        ...style,
      }}
    >
      {children}
    </div>
  );
}

// 垂直列表焦点导航：ArrowUp/Down 移动，Enter 选，Escape/Backspace 返回。
export function useTvList(count, onSelect, opts = {}) {
  const { onBack } = opts;
  const [idx, setIdx] = useState(0);
  useEffect(() => {
    const onKey = (e) => {
      switch (e.key) {
        case 'ArrowUp': setIdx((v) => Math.max(0, v - 1)); e.preventDefault(); break;
        case 'ArrowDown': setIdx((v) => Math.min(count - 1, v + 1)); e.preventDefault(); break;
        case 'Enter': onSelect && onSelect(idx); e.preventDefault(); break;
        case 'Escape':
        case 'Backspace': onBack && onBack(); e.preventDefault(); break;
        default: break;
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [count, idx, onSelect, onBack]);
  return { idx, setIdx };
}
