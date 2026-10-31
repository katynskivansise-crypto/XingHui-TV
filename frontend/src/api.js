// 数据接入层：前端与引擎的唯一通道。
// 沙盒联调：vite proxy /api → Rust 引擎 HTTP 服务（examples/server.rs）。
// Tauri/安卓集成：把 fetch 换成 tauri.invoke / JNI 调用即可，契约（action+key+params）不变。

// 统一引擎调用：action 对应 Spider 方法（home/category/detail/search/player/live）
export async function engine(action, key = 'demo', params = {}) {
  const q = new URLSearchParams({ key, params: JSON.stringify(params) });
  const res = await fetch(`/api/${action}?${q}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export const home = (k = 'demo') => engine('home', k, {});
export const category = (tid, pg = '1', k = 'demo') => engine('category', k, { tid, pg });
export const detail = (ids, k = 'demo') => engine('detail', k, { ids });
export const search = (key, k = 'demo') => engine('search', k, { key });
export const player = (flag, id, k = 'demo') => engine('player', k, { flag, id });

// ---- P5：直播 / 聚合搜索 / 源管理 ----
export const live = (k = 'demo') => engine('live', k, {});
export const searchAll = (key) => engine('search_all', 'demo', { key });
export const sources = () => engine('sources', 'demo', {});
