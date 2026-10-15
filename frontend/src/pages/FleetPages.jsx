import { useEffect, useState } from 'react';
import { api, formatDateTime } from '../api.js';
import { useChat } from '../chat/ChatContext.jsx';
import { Link, navigate } from '../router.jsx';
import Icon from '../ui/Icon.jsx';
import { PageHead } from './AgentPages.jsx';

const STATUSES = ['Moving', 'Idle', 'Stopped', 'Offline', 'In workshop'];

export function MyFleetPage({ query }) {
  const [summary, setSummary] = useState(null);
  const [vehicles, setVehicles] = useState(null);
  const [filters, setFilters] = useState({ q: '', status: '', fuel: '' });
  const selected = query.get('v');

  useEffect(() => {
    api.get('/api/fleet/summary').then(setSummary);
  }, []);

  useEffect(() => {
    const t = setTimeout(() => {
      const p = new URLSearchParams(Object.entries(filters).filter(([, v]) => v));
      api.get(`/api/fleet/vehicles?${p}`).then(setVehicles);
    }, 150);
    return () => clearTimeout(t);
  }, [filters]);

  const tiles = summary && [
    ['Vehicles', summary.vehicles],
    ['Moving', summary.byStatus.Moving || 0],
    ['Idle / stopped', (summary.byStatus.Idle || 0) + (summary.byStatus.Stopped || 0)],
    ['Offline', summary.byStatus.Offline || 0],
    ['In workshop', summary.byStatus['In workshop'] || 0],
    ['Distance, 7 days', `${summary.km7d.toLocaleString()} km`],
    ['High-priority issues', summary.attention.HIGH],
  ];

  return (
    <div className="page wide">
      <PageHead title="My fleet" subtitle={summary?.demo ? 'Demo fleet of 40 sample vehicles. Connect your telematics to see real data.' : 'Live status of every vehicle.'} />
      <div className="kpis">
        {(tiles || []).map(([label, value]) => (
          <div key={label} className={label === 'High-priority issues' && value > 0 ? 'kpi alert' : 'kpi'}>
            <span>{label}</span><strong>{value}</strong>
          </div>
        ))}
      </div>
      <div className="toolbar">
        <input className="search-input" value={filters.q} onChange={(e) => setFilters({ ...filters, q: e.target.value })}
          placeholder="Search registration, model, depot or driver" aria-label="Search vehicles" />
        <select value={filters.status} onChange={(e) => setFilters({ ...filters, status: e.target.value })} aria-label="Status">
          <option value="">All statuses</option>
          {STATUSES.map((s) => <option key={s}>{s}</option>)}
        </select>
        <select value={filters.fuel} onChange={(e) => setFilters({ ...filters, fuel: e.target.value })} aria-label="Fuel">
          <option value="">All fuels</option>
          {['Diesel', 'CNG', 'Electric'].map((s) => <option key={s}>{s}</option>)}
        </select>
      </div>
      <div className="table-scroll">
        <table className="data-table">
          <thead>
            <tr><th>Vehicle</th><th>Model</th><th>Type</th><th>Depot</th><th>Status</th><th>Driver</th><th>7 days</th><th>Efficiency</th><th>Energy</th><th>Faults</th></tr>
          </thead>
          <tbody>
            {(vehicles || []).map((v) => (
              <tr key={v.id} onClick={() => navigate(`/fleet?v=${v.id}`)} className={selected === v.id ? 'selected' : ''}>
                <td><strong>{v.regNo}</strong><div className="muted">{v.id}</div></td>
                <td>{v.make} {v.model}</td>
                <td>{v.type}<div className="muted">{v.fuel}</div></td>
                <td>{v.depot}</td>
                <td><span className={`status-pill ${v.status.replace(/\s/g, '-').toLowerCase()}`}>{v.status}</span></td>
                <td>{v.driver?.name || <span className="muted">Unassigned</span>}</td>
                <td>{v.km7d.toLocaleString()} km<div className="muted">{v.idlePct7d}% idle</div></td>
                <td>{v.efficiency} {unit(v)}</td>
                <td>{v.fuel === 'Electric' ? <Meter value={v.socPct} label={`${v.socPct}%`} /> : <Meter value={v.fuelLevelPct} label={`${v.fuelLevelPct}%`} />}</td>
                <td>{v.faults.length ? <span className="sev HIGH">{v.faults.length}</span> : <span className="muted">—</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {selected && <VehicleDrawer id={selected} onClose={() => navigate('/fleet')} />}
    </div>
  );
}

function VehicleDrawer({ id, onClose }) {
  const [data, setData] = useState(null);
  const { reset, ask } = useChat();
  useEffect(() => {
    setData(null);
    api.get(`/api/fleet/vehicles/${id}`).then(setData).catch(() => setData({ error: true }));
  }, [id]);

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const v = data?.vehicle;
  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label="Vehicle details">
        <button type="button" className="icon-btn drawer-close" onClick={onClose} aria-label="Close"><Icon name="close" /></button>
        {!data && <div className="loading"><span className="spinner" /> Loading…</div>}
        {data?.error && <p className="empty">Vehicle not found.</p>}
        {v && (
          <>
            <h2>{v.regNo}</h2>
            <p className="muted">{v.make} {v.model} · {v.type} · {v.fuel} · {v.year} · {v.depot} depot</p>
            <button type="button" className="btn primary" onClick={() => {
              reset();
              ask(`Give me a full health and compliance check of vehicle ${v.regNo} (${v.id}) and what I should do next.`, 'analysis');
              navigate('/chat');
            }}><Icon name="sparkle" size={16} /> Ask AI+ about this vehicle</button>

            <dl className="facts">
              <dt>Status</dt><dd>{v.status} · seen {formatDateTime(v.lastSeen)}</dd>
              <dt>Driver</dt><dd>{v.driver ? `${v.driver.name} · safety score ${v.driver.safetyScore} · licence to ${v.driver.licenceExpiry}` : 'Not assigned'}</dd>
              <dt>VIN</dt><dd>{v.vin || <span className="sev MEDIUM">missing</span>}</dd>
              <dt>Odometer</dt><dd>{v.odometerKm.toLocaleString()} km</dd>
              <dt>Last 7 days</dt><dd>{v.km7d.toLocaleString()} km · {v.idlePct7d}% idling · {v.efficiency} {unit(v)}</dd>
              <dt>Service</dt><dd>Next at {v.nextServiceDueKm.toLocaleString()} km or {v.nextServiceDate || 'no date'} · last {v.lastServiceDate}</dd>
              {v.fuel === 'Electric' ? (
                <><dt>Battery</dt><dd>{v.socPct}% charge (needs {v.requiredSocPct}% for {v.nextDeparture?.replace('T', ' ')}) · health {v.batteryHealthPct}% · {v.chargingStatus}</dd></>
              ) : (
                <><dt>Fuel</dt><dd>{v.fuelLevelPct}%{v.adBlueLevelPct != null ? ` · AdBlue ${v.adBlueLevelPct}%` : ''}{v.tyrePressureWarning ? ' · tyre pressure warning' : ''}</dd></>
              )}
            </dl>

            <h3>Faults</h3>
            {v.faults.length === 0 ? <p className="muted">No active faults.</p> : (
              <ul className="plain">{v.faults.map((f) => <li key={f.code}><span className={`sev ${f.severity === 'Critical' ? 'HIGH' : f.severity === 'Major' ? 'MEDIUM' : 'LOW'}`}>{f.code}</span> {f.description}</li>)}</ul>
            )}

            <h3>Documents and contracts</h3>
            <table className="data-table compact">
              <tbody>
                {v.documents.map((d) => {
                  const days = Math.round((new Date(d.expiry) - new Date()) / 86400000);
                  return (
                    <tr key={d.type}>
                      <td>{d.type}<div className="muted">{d.reference}</div></td>
                      <td>{d.expiry}</td>
                      <td>{days < 0 ? <span className="sev HIGH">expired</span> : days <= 30 ? <span className="sev MEDIUM">{days} days</span> : <span className="muted">{days} days</span>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>

            <h3>Open issues</h3>
            {data.findings.length === 0 ? <p className="muted">None.</p> : (
              <ul className="plain">{data.findings.map((f) => <li key={f.id}><span className={`sev ${f.severity}`}>{f.severity.toLowerCase()}</span> {f.title}: {f.action}</li>)}</ul>
            )}
          </>
        )}
      </aside>
    </>
  );
}

function Meter({ value, label }) {
  if (value == null) return <span className="muted">—</span>;
  return (
    <span className="meter" title={label}>
      <span style={{ width: `${value}%` }} className={value < 20 ? 'low' : ''} />
      <em>{label}</em>
    </span>
  );
}

function unit(v) {
  return v.fuel === 'Electric' ? 'km/kWh' : v.fuel === 'CNG' ? 'km/kg' : 'km/l';
}

const APPS = [
  { to: '/fleet', icon: 'fleet', name: 'My fleet', text: 'Live status, health and documents of every vehicle' },
  { to: '/agents', icon: 'agents', name: 'AI agents', text: 'Hygiene, compliance, operations, EV and reporting' },
  { to: '/custom-agents', icon: 'custom', name: 'Custom agents', text: 'Your own mix of checks' },
  { to: '/schedules', icon: 'calendar', name: 'Schedules', text: 'Automated reports and reminders' },
  { to: '/saved', icon: 'bookmark', name: 'Saved', text: 'Kept answers and reports' },
  { to: '/search', icon: 'search', name: 'Fleet search', text: 'Search the OEM feature matrix, STP and news' },
  { to: '/search?q=Latest%20fleet%20news', icon: 'news', name: 'Fleet news', text: 'Latest updates from 8 publishers' },
  { to: '/search?q=Compare%20Volvo%20vs%20Tata%20Motors', icon: 'matrix', name: 'OEM comparison', text: 'Connected services of 10 truck OEMs side by side' },
  { to: '/help', icon: 'help', name: 'Help & support', text: 'How AI+ works and what it can do' },
];

export function FleetAppsPage() {
  return (
    <div className="page">
      <PageHead title="Fleet apps" subtitle="Everything in Fleet Bharat, one click away." />
      <div className="apps-grid">
        {APPS.map((a) => (
          <Link key={a.name} to={a.to} className="app-tile">
            <span className="app-icon"><Icon name={a.icon} size={26} /></span>
            <strong>{a.name}</strong>
            <small>{a.text}</small>
          </Link>
        ))}
      </div>
    </div>
  );
}

export function HelpPage({ aiEnabled }) {
  return (
    <div className="page narrow">
      <PageHead title="Help & support" subtitle={aiEnabled ? 'AI answers are on.' : 'AI answers are off: the built-in agents answer instead.'} />
      <div className="help">
        <h3>Asking AI+</h3>
        <p>Type a question on the home page or press one of the suggestions. Pick a mode first if you want a particular kind of answer:</p>
        <ul>
          <li><strong>Search</strong>: quick answers from your fleet, the OEM research workbook, fleet news and the web.</li>
          <li><strong>Deep analysis</strong>: slower and more thorough; looks for patterns and root causes.</li>
          <li><strong>Create report</strong>: a structured report you can save and schedule.</li>
          <li><strong>Take action</strong>: proposes schedules, reminders and fixes. Nothing happens until you press Confirm.</li>
        </ul>
        <p>Follow-up questions keep the conversation's context. Use the bookmark under an answer to keep it in Saved. The microphone button
          dictates your question in browsers that support speech input.</p>

        <h3>AI agents</h3>
        <p>Five agents check the fleet continuously: Fleet Hygiene (data issues), Compliance (documents, contracts and licences),
          Vehicle Operations (faults, service, idling, fuel, AdBlue, tyres, connectivity), EV Operations (charging readiness,
          charger faults, battery health) and Reporting (weekly report). Open an agent to see its findings; mark a finding
          resolved once it is handled.</p>

        <h3>Custom agents and schedules</h3>
        <p>A custom agent combines any checks for the vehicles you choose (for example only electric buses in one depot).
          Any agent or custom agent can run on a schedule; results arrive under the bell and in Saved. Reminders work the same way.</p>

        <h3>Data</h3>
        <p>This installation uses a <strong>demo fleet</strong> of 40 generated vehicles so every feature has something to show.
          The OEM comparison comes from the Commercial Vehicle Feature Matrix workbook, and news from publisher RSS feeds.
          AI answers are generated by Claude and can be wrong; check important facts.</p>

        <h3>Classic search</h3>
        <p>The earlier search page is still available under <Link to="/search">Classic search</Link> in the menu.</p>
      </div>
    </div>
  );
}
