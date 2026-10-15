import { useEffect, useMemo, useRef, useState } from 'react';
import AiOverview from './AiOverview.jsx';
import RichText from './RichText.jsx';
import { LatestUpdates, NewsList, TopStories, useNews } from './News.jsx';

const TABS = [
  { key: 'All', sheets: null },
  { key: 'News', sheets: null },
  { key: 'Features', sheets: ['Feature Matrix', 'Feature Dictionary'] },
  { key: 'Evidence', sheets: ['Evidence'] },
  { key: 'OEMs & STP', sheets: ['OEM STP', 'Project STP', 'Customer Segments'] },
  { key: 'Packages', sheets: ['Service Packages', 'Validation Checklist'] },
  { key: 'Sources', sheets: ['Sources', 'Read Me'] },
];

const SAMPLE_QUERIES = [
  'Latest fleet news',
  'Which OEMs support geofence alerts?',
  'Compare Volvo vs Tata Motors',
  'Tell me about TRATON',
  'Electric depot fleet',
  'Uptime package',
];

const AI_SAMPLE_QUERIES = [
  'Diesel vs electric truck total cost of ownership',
  'Best telematics features for a 50-truck fleet',
  'Tata Prima vs Ashok Leyland AVTR',
];

function readQueryFromUrl() {
  return new URLSearchParams(window.location.search).get('q') || '';
}

export default function App() {
  const [query, setQuery] = useState(readQueryFromUrl);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [tab, setTab] = useState('All');
  const [info, setInfo] = useState(null);

  useEffect(() => {
    fetch('/api/info').then((r) => (r.ok ? r.json() : null)).then(setInfo).catch(() => {});
  }, []);

  const runSearch = async (q, { push = true, lucky = false } = {}) => {
    const text = q.trim();
    if (!text) return;
    setQuery(text);
    setTab('All');
    if (push) window.history.pushState({}, '', `?q=${encodeURIComponent(text)}`);
    setLoading(true);
    setError('');
    try {
      const res = await fetch(`/api/search?q=${encodeURIComponent(text)}`);
      if (!res.ok) throw new Error(`Search failed (${res.status})`);
      const json = await res.json();
      setData(json);
      if (lucky) {
        const top = json.results.find((r) => r.url);
        if (top) window.open(top.url, '_blank', 'noopener');
      }
    } catch (e) {
      setError(e.message.includes('fetch') ? 'Cannot reach the search server. Is the backend running on port 8090?' : e.message);
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const initial = readQueryFromUrl();
    if (initial) runSearch(initial, { push: false });
    const onPop = () => {
      const q = readQueryFromUrl();
      if (q) runSearch(q, { push: false });
      else goHome(false);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const goHome = (push = true) => {
    setData(null);
    setQuery('');
    setError('');
    if (push) window.history.pushState({}, '', window.location.pathname);
  };

  const onResultsPage = data || loading || error;

  if (!onResultsPage) {
    return (
      <div className="home">
        <main className="home-main">
          <Logo size="large" />
          <SearchBox value={query} onChange={setQuery} onSubmit={(q) => runSearch(q)} autoFocus />
          <div className="home-buttons">
            <button type="button" onClick={() => runSearch(query)}>Fleet Search</button>
            <button type="button" onClick={() => runSearch(query, { lucky: true })}>I'm Feeling Lucky</button>
          </div>
          <div className="samples">
            Try:{' '}
            {(info?.aiEnabled ? [...SAMPLE_QUERIES, ...AI_SAMPLE_QUERIES] : SAMPLE_QUERIES).map((s) => (
              <button key={s} type="button" className="link-button" onClick={() => runSearch(s)}>{s}</button>
            ))}
          </div>
          {info && (
            <p className="ai-note">
              {info.aiEnabled
                ? `✦ AI answers on${info.webSearch ? ' · with web search' : ''}: ask anything about fleets and commercial vehicles`
                : 'Answers come from the workbook. Set ANTHROPIC_API_KEY on the server to turn on AI answers.'}
            </p>
          )}
          <LatestUpdates onSearch={(q) => runSearch(q)} aiEnabled={info?.aiEnabled} />
        </main>
        <Footer />
      </div>
    );
  }

  return (
    <div className="results-page">
      <header className="results-header">
        <button type="button" className="logo-button" onClick={() => goHome()} aria-label="Fleet Bharat home">
          <Logo size="small" />
        </button>
        <SearchBox value={query} onChange={setQuery} onSubmit={(q) => runSearch(q)} compact />
      </header>
      <nav className="tabs">
        {TABS.map((t) => (
          <button key={t.key} type="button" className={t.key === tab ? 'tab active' : 'tab'} onClick={() => setTab(t.key)}>
            {t.key}
          </button>
        ))}
      </nav>
      <main className="results-main">
        {loading && <div className="loading"><span className="spinner" /> Searching the fleet workbook…</div>}
        {error && <div className="error">{error}</div>}
        {data && !loading && <Results data={data} tab={tab} setTab={setTab} onSearch={runSearch} />}
      </main>
      <Footer />
    </div>
  );
}

function Results({ data, tab, setTab, onSearch }) {
  const sheets = TABS.find((t) => t.key === tab)?.sheets;
  const results = sheets ? data.results.filter((r) => sheets.includes(r.sheet)) : data.results;
  const terms = useMemo(
    () => data.query.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((t) => t.length > 2),
    [data.query]
  );
  const news = useNews(data.query, 30);

  // The AI overview stays mounted while other tabs are shown so its answer is not regenerated.
  const aiOverview = data.aiEnabled && (
    <div hidden={tab !== 'All'}>
      <AiOverview key={data.query} query={data.query} onSearch={onSearch} />
    </div>
  );

  if (tab === 'News') {
    return (
      <>
        {aiOverview}
        <p className="stats">
          {news ? `${news.items.length} recent ${news.items.length === 1 ? 'story' : 'stories'} from ${news.sources.length} fleet publishers` : ' '}
        </p>
        <NewsList page={news} />
      </>
    );
  }

  return (
    <>
      <p className="stats">
        About {results.length} result{results.length === 1 ? '' : 's'} ({(data.tookMs / 1000).toFixed(2)} seconds)
      </p>
      {aiOverview}
      {tab === 'All' && data.answer && <AnswerBox answer={data.answer} secondary={data.aiEnabled} />}
      {tab === 'All' && <TopStories items={news?.items} onMore={() => setTab('News')} />}
      {results.length === 0 && (
        <div className="no-results">
          <p>Your search - <b>{data.query}</b> - did not match any fleet records.</p>
          <p>Suggestions:</p>
          <ul>
            <li>Try a feature name, like <button type="button" className="link-button" onClick={() => onSearch('geofence')}>geofence</button></li>
            <li>Try an OEM, like <button type="button" className="link-button" onClick={() => onSearch('Volvo')}>Volvo</button> or <button type="button" className="link-button" onClick={() => onSearch('Tata Motors')}>Tata Motors</button></li>
            <li>Use fewer or more general words.</li>
          </ul>
        </div>
      )}
      <ol className="result-list">
        {results.map((r) => <ResultItem key={r.id} result={r} terms={terms} />)}
      </ol>
    </>
  );
}

function AnswerBox({ answer, secondary }) {
  const [expanded, setExpanded] = useState(false);
  const [open, setOpen] = useState(!secondary);
  const long = answer.text.length > 700;
  if (!open) {
    return (
      <button type="button" className="workbook-toggle" onClick={() => setOpen(true)}>
        ▸ Quick facts from the workbook
      </button>
    );
  }
  return (
    <section className={secondary ? 'answer workbook' : 'answer'}>
      <div className="answer-head">
        <span className="answer-icon" aria-hidden>{secondary ? '▤' : '✦'}</span>
        <span className="answer-title">{secondary ? 'From the workbook' : 'Fleet overview'}</span>
        <span className="badge">Workbook</span>
      </div>
      <div className={long && !expanded ? 'answer-body clamped' : 'answer-body'}>
        <RichText text={answer.text} />
      </div>
      {long && (
        <button type="button" className="show-more" onClick={() => setExpanded(!expanded)}>
          {expanded ? 'Show less ▲' : 'Show more ▼'}
        </button>
      )}
      {answer.sources?.length > 0 && (
        <div className="answer-sources">
          {answer.sources.slice(0, 6).map((s) => (
            <a key={s.url} href={s.url} target="_blank" rel="noreferrer" className="source-chip" title={s.url}>
              <span className="source-id">{s.label}</span> {hostname(s.url)}
            </a>
          ))}
        </div>
      )}
    </section>
  );
}

function ResultItem({ result, terms }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="result">
      <div className="crumb">
        <span className="crumb-icon" aria-hidden>{result.sheet.charAt(0)}</span>
        <div>
          <div className="crumb-sheet">{result.sheet}</div>
          <div className="crumb-url">{result.url ? hostname(result.url) : 'Commercial Vehicle Feature Matrix › ' + result.sheet}</div>
        </div>
      </div>
      <h3>
        {result.url ? (
          <a href={result.url} target="_blank" rel="noreferrer"><Highlight text={result.title} terms={terms} /></a>
        ) : (
          <button type="button" className="title-button" onClick={() => setOpen(!open)}>
            <Highlight text={result.title} terms={terms} />
          </button>
        )}
      </h3>
      <p className="snippet"><Highlight text={result.snippet} terms={terms} /></p>
      <button type="button" className="details-toggle" onClick={() => setOpen(!open)}>
        {open ? 'Hide details' : 'All fields'}
      </button>
      {open && (
        <table className="fields">
          <tbody>
            {Object.entries(result.fields).map(([k, v]) => (
              <tr key={k}>
                <th>{k}</th>
                <td>{v.startsWith('http') ? <Links text={v} /> : <Highlight text={v} terms={terms} />}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </li>
  );
}

function SearchBox({ value, onChange, onSubmit, autoFocus, compact }) {
  const [suggestions, setSuggestions] = useState([]);
  const [active, setActive] = useState(-1);
  const [focused, setFocused] = useState(false);
  const inputRef = useRef(null);

  useEffect(() => {
    if (!focused || value.trim().length < 2) {
      setSuggestions([]);
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      fetch(`/api/suggest?q=${encodeURIComponent(value)}`, { signal: controller.signal })
        .then((r) => (r.ok ? r.json() : []))
        .then((list) => { setSuggestions(list); setActive(-1); })
        .catch(() => {});
    }, 120);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [value, focused]);

  const submit = (q) => {
    setSuggestions([]);
    inputRef.current?.blur();
    onSubmit(q);
  };

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown' && suggestions.length) {
      e.preventDefault();
      setActive((a) => (a + 1) % suggestions.length);
    } else if (e.key === 'ArrowUp' && suggestions.length) {
      e.preventDefault();
      setActive((a) => (a <= 0 ? suggestions.length - 1 : a - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      submit(active >= 0 ? suggestions[active] : value);
    } else if (e.key === 'Escape') {
      setSuggestions([]);
    }
  };

  const showList = focused && suggestions.length > 0;

  return (
    <div className={`searchbox ${compact ? 'compact' : ''} ${showList ? 'open' : ''}`}>
      <div className="searchbox-row">
        <svg className="search-icon" viewBox="0 0 24 24" aria-hidden>
          <path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
        </svg>
        <input
          ref={inputRef}
          value={value}
          autoFocus={autoFocus}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          onFocus={() => setFocused(true)}
          onBlur={() => setTimeout(() => setFocused(false), 150)}
          placeholder="Search fleet features, OEMs, segments…"
          aria-label="Search"
          role="combobox"
          aria-expanded={showList}
          aria-autocomplete="list"
        />
        {value && (
          <button type="button" className="clear" onClick={() => { onChange(''); inputRef.current?.focus(); }} aria-label="Clear">×</button>
        )}
      </div>
      {showList && (
        <ul className="suggestions" role="listbox">
          {suggestions.map((s, i) => (
            <li
              key={s}
              role="option"
              aria-selected={i === active}
              className={i === active ? 'active' : ''}
              onMouseDown={(e) => { e.preventDefault(); onChange(s); submit(s); }}
            >
              <svg className="search-icon small" viewBox="0 0 24 24" aria-hidden>
                <path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
              </svg>
              {s}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Logo({ size }) {
  return <img className={`logo ${size}`} src="/fleet-bharat-logo.png" alt="Fleet Bharat" />;
}

function Footer() {
  return (
    <footer className="footer">
      <span>Commercial Vehicle Feature Matrix &amp; STP · research snapshot 09 Oct 2026</span>
      <span>D Documented · P Partner · A Announced · NV Not verified</span>
    </footer>
  );
}

function Highlight({ text, terms }) {
  if (!text || !terms.length) return text || null;
  const escaped = terms.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  const re = new RegExp(`(${escaped.join('|')})`, 'gi');
  return text.split(re).map((part, i) => (i % 2 === 1 ? <b key={i}>{part}</b> : part));
}

function Links({ text }) {
  return text.split(/\s+/).filter(Boolean).map((u) => (
    <div key={u}><a href={u} target="_blank" rel="noreferrer">{u}</a></div>
  ));
}

function hostname(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}
