import { useEffect, useState } from 'react';

/** Fetches /api/news (optionally filtered by a query); returns null until loaded. */
export function useNews(query, limit) {
  const [page, setPage] = useState(null);
  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ limit: String(limit) });
    if (query) params.set('q', query);
    setPage(null);
    fetch(`/api/news?${params}`, { signal: controller.signal })
      .then((r) => (r.ok ? r.json() : null))
      .then(setPage)
      .catch(() => {});
    return () => controller.abort();
  }, [query, limit]);
  return page;
}

/** Home page: newest stories from all feeds. */
export function LatestUpdates({ onSearch, aiEnabled }) {
  const [limit, setLimit] = useState(6);
  const page = useNews('', 18);
  if (!page) return <section className="latest"><h2>Latest fleet updates</h2><p className="news-meta">Loading the latest stories…</p></section>;
  if (!page.items.length) return null;
  return (
    <section className="latest">
      <div className="latest-head">
        <h2>Latest fleet updates</h2>
        <span className="news-meta">
          {page.sources.length} publishers{page.updatedAt ? ` · updated ${timeAgo(page.updatedAt)}` : ''}
        </span>
      </div>
      <div className="news-grid">
        {page.items.slice(0, limit).map((item) => <NewsCard key={item.url} item={item} />)}
      </div>
      <div className="latest-actions">
        {limit < page.items.length && (
          <button type="button" className="pill" onClick={() => setLimit(page.items.length)}>More updates</button>
        )}
        {aiEnabled && (
          <button type="button" className="pill" onClick={() => onSearch('Summarize the latest fleet news')}>✦ Summarize today's fleet news</button>
        )}
      </div>
    </section>
  );
}

/** Results page, "All" tab: a horizontal strip of stories matching the search. */
export function TopStories({ items, onMore }) {
  if (!items?.length) return null;
  return (
    <section className="top-stories">
      <h2>Top stories</h2>
      <div className="stories-strip">
        {items.slice(0, 8).map((item) => <NewsCard key={item.url} item={item} compact />)}
      </div>
      <button type="button" className="more-news" onClick={onMore}>More news →</button>
    </section>
  );
}

/** Results page, "News" tab. */
export function NewsList({ page }) {
  if (!page) return <div className="loading"><span className="spinner" /> Loading news…</div>;
  if (!page.items.length) {
    return <p className="no-results">No recent stories in the fleet news feeds match this search.</p>;
  }
  return (
    <ol className="news-list">
      {page.items.map((item) => (
        <li key={item.url}>
          <div className="news-text">
            <div className="news-source">{item.source}{item.publishedAt ? ` · ${timeAgo(item.publishedAt)}` : ''}</div>
            <a href={item.url} target="_blank" rel="noreferrer" className="news-title">{item.title}</a>
            {item.summary && <p className="snippet">{item.summary}</p>}
          </div>
          <Thumb src={item.imageUrl} className="news-thumb" />
        </li>
      ))}
    </ol>
  );
}

function NewsCard({ item, compact }) {
  return (
    <a href={item.url} target="_blank" rel="noreferrer" className={compact ? 'news-card compact' : 'news-card'}>
      <Thumb src={item.imageUrl} className="news-card-img" />
      <div className="news-card-body">
        <div className="news-source">{item.source}</div>
        <div className="news-card-title">{item.title}</div>
        {item.publishedAt && <div className="news-time">{timeAgo(item.publishedAt)}</div>}
      </div>
    </a>
  );
}

function Thumb({ src, className }) {
  const [failed, setFailed] = useState(false);
  if (!src || failed) return <div className={`${className} placeholder`} aria-hidden>🚚</div>;
  return <img src={src} alt="" className={className} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} />;
}

export function timeAgo(iso) {
  const seconds = (Date.now() - new Date(iso).getTime()) / 1000;
  if (Number.isNaN(seconds)) return '';
  if (seconds < 60) return 'just now';
  const units = [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]];
  for (const [name, size] of units) {
    const n = Math.floor(seconds / size);
    if (n >= 1) return `${n} ${name}${n > 1 ? 's' : ''} ago`;
  }
  return 'just now';
}
