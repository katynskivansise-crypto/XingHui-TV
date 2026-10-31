import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { FocusCard, useTvGrid, useTvList } from './tv-nav.jsx';
import { home, category, detail, player, live, searchAll, sources } from './api.js';

// ─────────────────────────────────────────────────────────────
// P4+P5 核心体验前端：首页 / 分类 / 详情 / 搜索 / 播放 / 直播 / 多源配置
// 简单 state 路由（不引 react-router），顶层管理 view 与当前源 key。
// ─────────────────────────────────────────────────────────────

const GRID_COLS = 4;
const dark = '#0f1216';
const muted = '#8a919c';

// 可用订阅源（server.rs 注册）。安卓/Tauri 从订阅源 URL 加载后注入。
const SOURCES = [
  { key: 'demo', name: '演示源', desc: '本地完整演示数据（各页面可联调）' },
  { key: 'drpy', name: '真实豆瓣源', desc: 'samples/rules/drpy.js 真实 drpy 规则（沙盒豆瓣可能不可达）' },
];

export default function App() {
  const [view, setView] = useState('home');
  const [cur, setCur] = useState({ key: 'demo', tid: '', ids: '' });
  const nav = useCallback((v, extra = {}) => {
    setView(v);
    setCur((c) => ({ ...c, ...extra }));
    window.scrollTo(0, 0);
  }, []);

  const currentSource = SOURCES.find((s) => s.key === cur.key) || SOURCES[0];

  // P5 周边：收藏 / 历史（localStorage 持久化，可跨会话保留）
  const [favs, setFavs] = useState(() => { try { return JSON.parse(localStorage.getItem('tv_favs') || '[]'); } catch { return []; } });
  const [hist, setHist] = useState(() => { try { return JSON.parse(localStorage.getItem('tv_hist') || '[]'); } catch { return []; } });
  useEffect(() => localStorage.setItem('tv_favs', JSON.stringify(favs)), [favs]);
  useEffect(() => localStorage.setItem('tv_hist', JSON.stringify(hist)), [hist]);
  const toggleFav = useCallback((item) => {
    setFavs((f) => (f.some((x) => x.vod_id === item.vod_id) ? f.filter((x) => x.vod_id !== item.vod_id) : [item, ...f]));
  }, []);
  const pushHist = useCallback((item) => {
    setHist((h) => [item, ...h.filter((x) => x.vod_id !== item.vod_id)].slice(0, 100));
  }, []);

  return (
    <div style={{
      background: `radial-gradient(1200px 600px at 20% -10%, #1a2230 0%, ${dark} 55%)`,
      color: '#e8ecf1', minHeight: '100vh', fontFamily: 'system-ui, "PingFang SC", sans-serif',
      padding: '24px 36px', userSelect: 'none',
    }}>
      <header style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 22, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 28, fontWeight: 700, color: '#ffd24d' }}>星汇 TV</span>
        <span style={{ fontSize: 12, color: muted, padding: '3px 12px', border: '1px solid #2a3445', borderRadius: 6 }}>
          {currentSource.name}
        </span>
        <span style={{ flex: 1 }} />
        <TopBtn active={view === 'home'} onClick={() => nav('home')}>🏠 首页</TopBtn>
        <TopBtn active={view === 'live'} onClick={() => nav('live')}>📡 直播</TopBtn>
        <TopBtn active={view === 'search'} onClick={() => nav('search')}>🔍 搜索</TopBtn>
        <TopBtn active={view === 'my'} onClick={() => nav('my')}>⭐ 我的</TopBtn>
        <TopBtn active={view === 'config'} onClick={() => nav('config')}>⚙ 多源</TopBtn>
      </header>

      {view === 'home' && <HomePage key={`${view}-${cur.key}`} cur={cur} nav={nav} />}
      {view === 'category' && <CategoryPage key={`${view}-${cur.key}-${cur.tid}`} cur={cur} nav={nav} />}
      {view === 'detail' && <DetailPage key={`${view}-${cur.key}-${cur.ids}`} cur={cur} nav={nav} favs={favs} toggleFav={toggleFav} />}
      {view === 'player' && <PlayerPage key={`${view}-${cur.ids}`} cur={cur} nav={nav} pushHist={pushHist} />}
      {view === 'live' && <LivePage cur={cur} nav={nav} />}
      {view === 'search' && <SearchPage key={`${view}-${cur.key}`} cur={cur} nav={nav} />}
      {view === 'my' && <MyPage cur={cur} nav={nav} favs={favs} hist={hist} />}
      {view === 'config' && <ConfigPage cur={cur} nav={nav} />}
    </div>
  );
}

function TopBtn({ active, onClick, children }) {
  return (
    <FocusCard focused={active} onClick={onClick} style={{ padding: '8px 22px', borderRadius: 999, background: active ? '#ffd24d' : '#20262f', color: active ? '#1a1a1a' : muted, fontSize: 14, fontWeight: active ? 700 : 400 }}>
      {children}
    </FocusCard>
  );
}

// ── 首页：分类横排 + 视频网格 ──────────────────────────────────
function HomePage({ cur, nav }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    home(cur.key).then(setData).catch((e) => setError(String(e)));
  }, [cur.key]);

  const list = useMemo(() => (data?.list || []), [data]);
  const cats = useMemo(() => (data?.class || []), [data]);
  const rows = Math.max(1, Math.ceil(list.length / GRID_COLS));
  const { r, c } = useTvGrid(rows, GRID_COLS, {
    onSelect: ({ r: rr, c: cc }) => {
      const item = list[rr * GRID_COLS + cc];
      if (item) nav('detail', { ids: item.vod_id });
    },
  });
  const focusIdx = Math.min(r * GRID_COLS + c, Math.max(0, list.length - 1));

  return (
    <div>
      <p style={{ margin: '2px 0 12px', color: muted, fontSize: 13 }}>
        {error ? `⚠️ ${error}（引擎未启动则进配置页确认源）` : data === null ? '加载中…' : `方向键移动 · Enter 详情 · 首页 ${list.length} 条`}
      </p>
      <nav style={{ display: 'flex', gap: 12, marginBottom: 22, overflowX: 'auto', paddingBottom: 6 }}>
        {cats.map((cat) => (
          <FocusCard key={cat.type_id} onClick={() => nav('category', { tid: cat.type_id })} style={{ padding: '10px 22px', borderRadius: 8, background: '#171c24', whiteSpace: 'nowrap' }}>
            {cat.type_name}
          </FocusCard>
        ))}
      </nav>
      {data !== null && !error && (
        <div style={{ display: 'grid', gridTemplateColumns: `repeat(${GRID_COLS}, 1fr)`, gap: 16 }}>
          {list.map((item, i) => (
            <FocusCard key={item.vod_id} focused={i === focusIdx} onClick={() => nav('detail', { ids: item.vod_id })}>
              <div style={{ aspectRatio: '3/4', background: `linear-gradient(160deg, #2a3445, #1a202a)`, borderRadius: 10, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', padding: 12 }}>
                <span style={{ fontSize: 16, fontWeight: 600 }}>{item.vod_name}</span>
                <span style={{ fontSize: 12, color: muted, marginTop: 4 }}>{item.vod_remarks || item.vod_year}</span>
              </div>
            </FocusCard>
          ))}
        </div>
      )}
    </div>
  );
}

// ── 分类列表 ─────────────────────────────────────────────────
function CategoryPage({ cur, nav }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    category(cur.tid, '1', cur.key).then(setData).catch((e) => setError(String(e)));
  }, [cur.tid, cur.key]);

  const list = useMemo(() => (data?.list || []), [data]);
  const { idx } = useTvList(list.length, (i) => {
    const item = list[i];
    if (item) nav('detail', { ids: item.vod_id });
  }, { onBack: () => nav('home') });

  return (
    <div>
      <p style={{ color: muted, fontSize: 13, margin: '2px 0 12px' }}>
        {error ? `⚠️ ${error}` : `分类列表 · Enter 详情 · ESC 返回 · 共 ${list.length} 条`}
      </p>
      {list.map((item, i) => (
        <FocusCard key={item.vod_id} focused={i === idx} onClick={() => nav('detail', { ids: item.vod_id })} style={{ padding: '12px 18px', marginBottom: 8, borderRadius: 8, background: '#171c24', display: 'flex', gap: 14, alignItems: 'center' }}>
          <span style={{ fontSize: 15, fontWeight: 600 }}>{item.vod_name}</span>
          <span style={{ flex: 1 }} />
          <span style={{ fontSize: 12, color: muted }}>{item.vod_remarks || ''}</span>
        </FocusCard>
      ))}
    </div>
  );
}

// ── 详情页（含多线路切换 + 收藏） ─────────────────────────────
function DetailPage({ cur, nav, favs, toggleFav }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [line, setLine] = useState(0);
  useEffect(() => {
    detail(cur.ids, cur.key).then(setData).catch((e) => setError(String(e)));
  }, [cur.ids, cur.key]);

  const vod = useMemo(() => (data?.list || [])[0] || {}, [data]);
  // 多线路：vod_play_from 用 $$$ 分隔线路名
  const lines = useMemo(() => (vod.vod_play_from || '线路1').split('$$$').map((s) => s.trim()).filter(Boolean), [vod]);
  // 当前线路的选集：vod_play_url 用 $$$ 分线路、$ 分"名$url"交替
  const curParts = useMemo(() => {
    const seg = ((vod.vod_play_url || '').split('$$$')[line] || (vod.vod_play_url || '').split('$$$')[0] || '').split('$').filter(Boolean);
    return seg;
  }, [vod, line]);
  const episodes = useMemo(() => curParts.filter((_, i) => i % 2 === 0), [curParts]);
  const playUrlOf = useCallback((i) => (curParts[i * 2 + 1] || curParts[i * 2] || ''), [curParts]);
  const currentLine = (lines[line] || '线路1');

  const { idx } = useTvList(Math.max(episodes.length, 1), (i) => {
    const u = playUrlOf(i);
    if (u) nav('player', { ids: cur.ids, tid: cur.tid, url: u, flag: currentLine });
  }, { onBack: () => nav('category', { tid: cur.tid }) });

  return (
    <div>
      <p style={{ color: muted, fontSize: 13, margin: '2px 0 14px' }}>详情 · 多线路切换 · Enter 播放选中集 · ESC 返回</p>
      {error ? <p style={{ color: '#f66' }}>⚠️ {error}</p> : (
        <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
          <div style={{ width: 180, aspectRatio: '3/4', background: 'linear-gradient(160deg,#2a3445,#1a202a)', borderRadius: 12 }} />
          <div style={{ flex: 1, minWidth: 260 }}>
            <div style={{ fontSize: 24, fontWeight: 700 }}>{vod.vod_name}</div>
            <FocusCard focused={false} onClick={() => toggleFav({ vod_id: vod.vod_id, vod_name: vod.vod_name, vod_remarks: vod.vod_remarks || '' })} style={{ display: 'inline-block', marginTop: 8, padding: '7px 20px', borderRadius: 999, background: favs.some((x) => x.vod_id === vod.vod_id) ? '#3a2a10' : '#20262f', color: favs.some((x) => x.vod_id === vod.vod_id) ? '#ffd24d' : muted, fontSize: 13 }}>
              {favs.some((x) => x.vod_id === vod.vod_id) ? '★ 已收藏' : '☆ 收藏'}
            </FocusCard>
            <div style={{ color: muted, fontSize: 13, margin: '6px 0' }}>
              {[vod.vod_year, vod.vod_area, vod.vod_remarks].filter(Boolean).join(' · ')}
            </div>
            <div style={{ fontSize: 13, color: muted }}>导演：{vod.vod_director || '-'}　主演：{vod.vod_actor || '-'}</div>
            <p style={{ fontSize: 13, color: '#b8c0cc', lineHeight: 1.7, marginTop: 10 }}>{vod.vod_content || '暂无简介'}</p>

            <div style={{ marginTop: 16, fontSize: 14, fontWeight: 700, color: '#ffd24d' }}>播放线路</div>
            <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              {lines.map((ln, i) => (
                <FocusCard key={i} focused={i === line} onClick={() => setLine(i)} style={{ padding: '8px 20px', borderRadius: 8, background: i === line ? '#ffd24d' : '#20262f', color: i === line ? '#1a1a1a' : muted, fontSize: 13 }}>
                  {ln}
                </FocusCard>
              ))}
            </div>

            <div style={{ marginTop: 14, fontSize: 14, fontWeight: 700, color: '#ffd24d' }}>选集（{currentLine} · {episodes.length}）</div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
              {episodes.map((ep, i) => (
                <FocusCard key={i} focused={i === idx} onClick={() => { const u = playUrlOf(i); if (u) nav('player', { ids: cur.ids, tid: cur.tid, url: u, flag: currentLine }); }} style={{ padding: '8px 18px', borderRadius: 8, background: i === idx ? '#ffd24d' : '#20262f', color: i === idx ? '#1a1a1a' : muted, fontSize: 13 }}>
                  {ep}
                </FocusCard>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ── 播放页（记历史 + 内核切换标注） ────────────────────────────
const KERNELS = ['HTML5(演示)', 'ExoPlayer(待接)', 'mpv(待接)'];
function PlayerPage({ cur, nav, pushHist }) {
  useEffect(() => { if (cur.url) pushHist({ vod_id: cur.ids, vod_name: cur.ids === 'live' ? cur.flag : (cur.ids || cur.flag || ''), url: cur.url, ts: Date.now() }); }, [cur.url, cur.ids, cur.flag]);
  const { idx } = useTvList(1, () => {}, { onBack: () => nav('detail', { ids: cur.ids, tid: cur.tid }) });
  const [kernel, setKernel] = useState(0);
  const url = cur.url || '';
  return (
    <div>
      <p style={{ color: muted, fontSize: 13, margin: '2px 0 12px' }}>播放页 · 内核切换演示（安卓端接原生 ijk/ExoPlayer/mpv）· ESC 返回</p>
      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        {KERNELS.map((k, i) => (
          <FocusCard key={i} focused={i === kernel} onClick={() => setKernel(i)} style={{ padding: '7px 20px', borderRadius: 999, background: i === kernel ? '#ffd24d' : '#20262f', color: i === kernel ? '#1a1a1a' : muted, fontSize: 13 }}>
            {k}
          </FocusCard>
        ))}
      </div>
      {kernel === 0 && (url ? (
        <video key={url} controls autoPlay style={{ width: '100%', maxHeight: '62vh', background: '#000', borderRadius: 10 }} src={url} />
      ) : (
        <p style={{ color: '#f66' }}>未取到播放地址</p>
      ))}
      {kernel > 0 && (
        <div style={{ height: '40vh', borderRadius: 10, background: '#0a0c0f', display: 'flex', alignItems: 'center', justifyContent: 'center', color: muted, fontSize: 15 }}>
          {KERNELS[kernel]} 内核 · 沙盒无安卓内核，接入后由原生壳渲染
        </div>
      )}
      <FocusCard focused={idx === 0} onClick={() => nav('detail', { ids: cur.ids, tid: cur.tid })} style={{ marginTop: 16, padding: '12px', borderRadius: 8, background: '#20262f', textAlign: 'center', color: muted }}>
        ← 返回详情
      </FocusCard>
    </div>
  );
}

// ── 搜索页（聚合多源） ────────────────────────────────────────
function SearchPage({ cur, nav }) {
  const [kw, setKw] = useState('');
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const doSearch = useCallback(() => {
    if (!kw.trim()) return;
    setBusy(true);
    searchAll(kw.trim()).then((d) => { setRes(d); setBusy(false); }).catch((e) => { setRes({ error: String(e) }); setBusy(false); });
  }, [kw]);

  const list = useMemo(() => (res?.list || []), [res]);
  const { idx } = useTvList(list.length, (i) => {
    const item = list[i];
    if (item) nav('detail', { ids: item.vod_id, key: item.vod_source || cur.key });
  }, { onBack: () => nav('home') });

  return (
    <div>
      <div style={{ display: 'flex', gap: 10, marginBottom: 14 }}>
        <input
          value={kw}
          onChange={(e) => setKw(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && doSearch()}
          placeholder="输入关键词，Enter 聚合搜索全部源"
          style={{ flex: 1, padding: '10px 16px', borderRadius: 8, background: '#171c24', border: '1px solid #2a3445', color: '#e8ecf1', fontSize: 15, outline: 'none' }}
        />
        <FocusCard focused={false} onClick={doSearch} style={{ padding: '10px 26px', borderRadius: 8, background: '#ffd24d', color: '#1a1a1a', fontWeight: 700 }}>搜索</FocusCard>
      </div>
      <p style={{ color: muted, fontSize: 13, margin: '2px 0 12px' }}>
        {busy ? '搜索中…' : res?.error ? `⚠️ ${res.error}` : `聚合结果 ${list.length} 条（多源）`}
      </p>
      {list.map((item, i) => (
        <FocusCard key={item.vod_id + (item.vod_source || '')} focused={i === idx} onClick={() => nav('detail', { ids: item.vod_id, key: item.vod_source || cur.key })} style={{ padding: '12px 18px', marginBottom: 8, borderRadius: 8, background: '#171c24', display: 'flex', gap: 14, alignItems: 'center' }}>
          <span style={{ fontSize: 15, fontWeight: 600 }}>{item.vod_name}</span>
          <span style={{ flex: 1 }} />
          {item.vod_source && <span style={{ fontSize: 11, padding: '2px 10px', borderRadius: 999, background: '#24344a', color: '#9db4d8' }}>{item.vod_source}</span>}
        </FocusCard>
      ))}
    </div>
  );
}

// ── 多源配置（源列表 + 订阅源 URL 输入） ──────────────────────
function ConfigPage({ cur, nav }) {
  const [src, setSrc] = useState(null);
  useEffect(() => { sources().then(setSrc).catch(() => {}); }, []);
  const list = useMemo(() => (src?.sources?.length ? src.sources : SOURCES), [src]);
  const { idx } = useTvList(list.length, (i) => {
    const s = list[i];
    if (s && s.key) nav('home', { key: s.key });
  }, { onBack: () => nav('home') });
  const [subUrl, setSubUrl] = useState('');

  return (
    <div>
      <p style={{ color: muted, fontSize: 13, margin: '2px 0 12px' }}>多源配置 · 选择订阅源（Enter 切换） · ESC 返回</p>
      {list.map((s, i) => (
        <FocusCard key={s.key} focused={i === idx} onClick={() => nav('home', { key: s.key })} style={{ padding: '16px 20px', marginBottom: 10, borderRadius: 10, background: s.key === cur.key ? '#24344a' : '#171c24', display: 'flex', gap: 16, alignItems: 'center' }}>
          <span style={{ fontWeight: 700, minWidth: 80 }}>{s.name || s.key}</span>
          <span style={{ flex: 1, color: muted, fontSize: 13 }}>{s.desc || ''}</span>
          {s.alive === false && <span style={{ color: '#f66', fontSize: 12 }}>✕ 不可用</span>}
          {s.key === cur.key && <span style={{ color: '#4fd27b', fontSize: 13 }}>✓ 当前</span>}
        </FocusCard>
      ))}
      <div style={{ marginTop: 18, display: 'flex', gap: 10 }}>
        <input
          value={subUrl}
          onChange={(e) => setSubUrl(e.target.value)}
          placeholder="订阅源 URL（沙盒演示：展示已支持源；安卓运行时由订阅源解析注入）"
          style={{ flex: 1, padding: '10px 16px', borderRadius: 8, background: '#171c24', border: '1px solid #2a3445', color: '#e8ecf1', fontSize: 13, outline: 'none' }}
        />
        <FocusCard focused={false} onClick={() => setSubUrl('')} style={{ padding: '10px 22px', borderRadius: 8, background: '#20262f', color: muted, fontSize: 13 }}>清空</FocusCard>
      </div>
      <p style={{ marginTop: 16, color: muted, fontSize: 12, borderTop: '1px solid #22282f', paddingTop: 14 }}>
        安卓/Tauri：订阅源由外部加载（订阅源 URL 解析 → 规则 → 注入引擎），此处为沙盒演示已注册源。
      </p>
    </div>
  );
}

// ── 直播页 ──────────────────────────────────────────────────
function LivePage({ cur, nav }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [group, setGroup] = useState(0);
  useEffect(() => { live(cur.key).then(setData).catch((e) => setError(String(e))); }, [cur.key]);

  const groups = useMemo(() => (data?.list || []), [data]);
  const channels = useMemo(() => (groups[group]?.channels || []), [groups, group]);
  const { idx } = useTvList(Math.max(channels.length, 1), (i) => {
    const ch = channels[i];
    if (ch && ch.url) nav('player', { ids: 'live', tid: cur.tid, url: ch.url, flag: ch.name });
  }, { onBack: () => nav('home') });

  return (
    <div>
      <p style={{ color: muted, fontSize: 13, margin: '2px 0 12px' }}>
        {error ? `⚠️ ${error}` : `直播 · Enter 播放频道 · ESC 返回`}
      </p>
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        {groups.map((g, i) => (
          <FocusCard key={i} focused={i === group} onClick={() => setGroup(i)} style={{ padding: '8px 20px', borderRadius: 8, background: i === group ? '#ffd24d' : '#20262f', color: i === group ? '#1a1a1a' : muted, fontSize: 13 }}>
            {g.group}
          </FocusCard>
        ))}
      </div>
      {channels.map((ch, i) => (
        <FocusCard key={ch.name + i} focused={i === idx} onClick={() => ch.url && nav('player', { ids: 'live', tid: cur.tid, url: ch.url, flag: ch.name })} style={{ padding: '12px 18px', marginBottom: 8, borderRadius: 8, background: '#171c24' }}>
          {ch.name}
        </FocusCard>
      ))}
    </div>
  );
}

// ── 我的（收藏 / 历史，localStorage 持久化） ──────────────────
function MyPage({ cur, nav, favs, hist }) {
  const [tab, setTab] = useState(0); // 0 收藏 1 历史
  const list = tab === 0 ? favs : hist;
  const { idx } = useTvList(list.length, (i) => {
    const it = list[i];
    if (it && it.vod_id) nav('detail', { ids: it.vod_id });
  }, { onBack: () => nav('home') });

  return (
    <div>
      <p style={{ color: muted, fontSize: 13, margin: '2px 0 12px' }}>我的 · 收藏 / 历史 · Enter 详情 · ESC 返回</p>
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        <FocusCard focused={tab === 0} onClick={() => setTab(0)} style={{ padding: '8px 20px', borderRadius: 999, background: tab === 0 ? '#ffd24d' : '#20262f', color: tab === 0 ? '#1a1a1a' : muted, fontSize: 13 }}>收藏 {favs.length}</FocusCard>
        <FocusCard focused={tab === 1} onClick={() => setTab(1)} style={{ padding: '8px 20px', borderRadius: 999, background: tab === 1 ? '#ffd24d' : '#20262f', color: tab === 1 ? '#1a1a1a' : muted, fontSize: 13 }}>历史 {hist.length}</FocusCard>
      </div>
      {list.length === 0 ? (
        <p style={{ color: '#6a7280', fontSize: 14 }}>{tab === 0 ? '暂无收藏 · 在详情页点 ☆ 收藏' : '暂无历史 · 播放过的会记录在这里'}</p>
      ) : list.map((it, i) => (
        <FocusCard key={it.vod_id + i} focused={i === idx} onClick={() => nav('detail', { ids: it.vod_id })} style={{ padding: '12px 18px', marginBottom: 8, borderRadius: 8, background: '#171c24', display: 'flex', gap: 14, alignItems: 'center' }}>
          <span style={{ fontSize: 15, fontWeight: 600 }}>{it.vod_name}</span>
          <span style={{ flex: 1 }} />
          {it.ts && <span style={{ fontSize: 11, color: '#6a7280' }}>{new Date(it.ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</span>}
        </FocusCard>
      ))}
    </div>
  );
}
