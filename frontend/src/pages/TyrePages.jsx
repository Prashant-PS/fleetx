import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useChat } from '../chat/ChatContext.jsx';
import { navigate } from '../router.jsx';
import Icon from '../ui/Icon.jsx';
import { PageHead, ScheduleForm } from './AgentPages.jsx';

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
export const rupees = (n) => inr.format(n || 0);

const STATUS = {
  REPLACE_NOW: { label: 'Replace now', cls: 'replace' },
  DUE_30: { label: 'Due ≤ 30 days', cls: 'due30' },
  DUE_90: { label: 'Due ≤ 90 days', cls: 'due90' },
  ATTENTION: { label: 'Needs attention', cls: 'attention' },
  OK: { label: 'OK', cls: 'ok' },
};

export function TyresPage({ query }) {
  const [data, setData] = useState(null);
  const [urgency, setUrgency] = useState('ALL');
  const [scheduling, setScheduling] = useState(false);
  const [note, setNote] = useState('');
  const { reset, ask } = useChat();
  const selected = query.get('v');

  useEffect(() => {
    api.get('/api/tyres').then(setData);
  }, []);

  if (!data) return <div className="page"><div className="loading"><span className="spinner" /> Analysing tyres…</div></div>;

  const { emissions: e, costPlan: c, statusCounts: s } = data;
  const recs = data.recommendations.filter((r) => urgency === 'ALL' || r.urgency === urgency);

  const remind = async (r) => {
    const due = new Date(r.dueBy);
    due.setDate(due.getDate() - 3);
    const tomorrow = new Date(Date.now() + 86400000);
    const date = (due > tomorrow ? due : tomorrow).toISOString().slice(0, 10);
    try {
      await api.post('/api/schedules', {
        title: `${r.action} ${r.position} on ${r.vehicle.split(' ')[0]}`, taskType: 'reminder',
        message: `${r.action}: ${r.position} on ${r.vehicle}. ${r.reason} Budget ${rupees(r.costInr)}.`,
        cadence: 'ONCE', time: '09:00', date,
      });
      setNote(`Reminder set for ${date} at 09:00.`);
    } catch (err) {
      setNote(err.message);
    }
  };

  return (
    <div className="page wide">
      <PageHead icon="tyre" title="Tire Analytics"
        subtitle={`${data.tyres} tyres on ${data.vehicles} vehicles · tread life, emissions and a costed plan (estimates from demo data).`}>
        <button type="button" className="btn" onClick={() => setScheduling(!scheduling)}><Icon name="calendar" size={16} /> Schedule weekly check</button>
        <button type="button" className="btn primary" onClick={() => {
          reset();
          ask('What is my fleet tire status? Cover emissions, pending life and the cost of the suggested actions.', 'analysis');
          navigate('/chat');
        }}><Icon name="sparkle" size={16} /> Ask AI+</button>
      </PageHead>
      {scheduling && (
        <ScheduleForm preset={{ taskType: 'agent', taskRef: 'tyre-analytics', title: 'Weekly tyre check' }}
          onDone={() => {
            setScheduling(false);
            setNote('Weekly tyre check scheduled.');
          }} />
      )}
      {note && <div className="note">{note}</div>}

      <div className="kpis">
        <Kpi label="Replace now" value={s.REPLACE_NOW} alert={s.REPLACE_NOW > 0} />
        <Kpi label="Due within 30 days" value={s.DUE_30} warn={s.DUE_30 > 0} />
        <Kpi label="Due within 90 days" value={s.DUE_90} />
        <Kpi label="Healthy" value={s.OK} />
        <Kpi label="Average tread left" value={`${data.avgTreadLeftPct}%`} />
      </div>

      <div className="tyre-panels">
        <section className="panel">
          <h2><Icon name="leaf" size={18} /> Tyre emissions <small>this week, estimated</small></h2>
          <div className="metric-grid">
            <Metric value={`${e.wearParticlesKgPerWeek} kg`} label="Tyre-wear particles shed" hint={`${e.wearParticlesGPerKm} g per vehicle-km`} />
            <Metric value={`${e.extraCo2KgPerWeek} kg`} label="Extra CO₂ from under-inflation" hint={`${e.underInflatedTyres} tyres 10%+ low`} />
            <Metric value={rupees(e.extraEnergyCostInrPerWeek)} label="Fuel/energy wasted" hint="due to low pressure" />
          </div>
        </section>
        <section className="panel">
          <h2><Icon name="wallet" size={18} /> Cost plan <small>indicative prices</small></h2>
          <div className="metric-grid">
            <Metric value={rupees(c.next30DaysInr)} label="Budget, next 30 days" hint={`${rupees(c.next90DaysInr)} within 90 days`} />
            <Metric value={rupees(c.monthlySavingInr)} label="Saved per month" hint={`by fixing ${c.inflationFixCount} vehicles' pressures`} />
            <Metric value={rupees(c.retreadSavingInr)} label="Saved by retreading" hint={`${c.retreadCount} retreads vs ${c.replaceCount} new tyres`} />
          </div>
        </section>
      </div>

      <section className="section">
        <div className="section-head"><h2>Pending life <span>Vehicles with the soonest tyre replacements</span></h2></div>
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>Vehicle</th><th>Status</th><th>Lowest tread</th><th>Next replacement</th><th>Wear particles</th><th>Planned cost (90 days)</th></tr></thead>
            <tbody>
              {data.vehiclesAtRisk.slice(0, 15).map((v) => (
                <tr key={v.vehicleId} onClick={() => navigate(`/tyres?v=${v.vehicleId}`)} className={selected === v.vehicleId ? 'selected' : ''}>
                  <td><strong>{v.vehicle}</strong></td>
                  <td><TyreStatus status={v.status} /></td>
                  <td>{v.minTreadMm} mm</td>
                  <td>{v.nextReplacementDays == null ? '—' : v.nextReplacementDays === 0 ? 'Now' : `In ${v.nextReplacementDays} days`}</td>
                  <td>{v.wearParticlesKgPerWeek} kg/week</td>
                  <td>{rupees(v.plannedCostInr)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="section">
        <div className="section-head">
          <h2>Suggested actions <span>{data.recommendations.length} actions, most urgent first</span></h2>
        </div>
        <div className="filter-chips">
          {['ALL', 'HIGH', 'MEDIUM', 'LOW'].map((u) => (
            <button key={u} type="button" className={urgency === u ? 'chip active' : 'chip'} onClick={() => setUrgency(u)}>
              {u === 'ALL' ? 'All' : u.toLowerCase()} ({u === 'ALL' ? data.recommendations.length : data.recommendations.filter((r) => r.urgency === u).length})
            </button>
          ))}
        </div>
        <ul className="findings">
          {recs.slice(0, 40).map((r, i) => (
            <li key={`${r.vehicleId}-${r.position}-${r.action}-${i}`} className="finding">
              <span className={`sev ${r.urgency}`}>{r.urgency.toLowerCase()}</span>
              <div className="finding-main">
                <div className="finding-title">{r.action} · {r.position}</div>
                <a className="finding-vehicle" href={`/tyres?v=${r.vehicleId}`} onClick={(ev) => { ev.preventDefault(); navigate(`/tyres?v=${r.vehicleId}`); }}>{r.vehicle}</a>
                <div className="finding-detail">{r.reason}</div>
                <div className="cost-line">
                  <span><strong>{r.costInr ? rupees(r.costInr) : 'No cost'}</strong></span>
                  {r.monthlySavingInr > 0 && <span className="saving">saves ≈{rupees(r.monthlySavingInr)}/month</span>}
                  {r.dueBy && <span className="muted">by {new Date(r.dueBy).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}</span>}
                </div>
              </div>
              <div className="finding-buttons">
                <button type="button" className="btn small" onClick={() => remind(r)}><Icon name="bell" size={14} /> Remind me</button>
              </div>
            </li>
          ))}
        </ul>
        {recs.length > 40 && <p className="fine-print">Showing 40 of {recs.length}.</p>}
      </section>

      <details className="assumptions">
        <summary>How these numbers are estimated</summary>
        <ul>{data.assumptions.map((a) => <li key={a}>{a}</li>)}</ul>
      </details>

      {selected && <TyreDrawer id={selected} onClose={() => navigate('/tyres')} />}
    </div>
  );
}

function TyreDrawer({ id, onClose }) {
  useEffect(() => {
    const onKey = (ev) => ev.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label="Vehicle tyres">
        <button type="button" className="icon-btn drawer-close" onClick={onClose} aria-label="Close"><Icon name="close" /></button>
        <VehicleTyres id={id} showTitle />
      </aside>
    </>
  );
}

/** Axle diagram, per-tyre detail and suggested actions for one vehicle. Also used in the My fleet drawer. */
export function VehicleTyres({ id, showTitle }) {
  const [data, setData] = useState(null);
  const [picked, setPicked] = useState(null);
  useEffect(() => {
    setData(null);
    setPicked(null);
    api.get(`/api/tyres/vehicles/${id}`).then(setData).catch(() => setData({ error: true }));
  }, [id]);

  if (!data) return <div className="loading"><span className="spinner" /> Loading tyres…</div>;
  if (data.error) return <p className="empty">No tyre data for this vehicle.</p>;

  const rows = groupAxles(data.detail);
  const pick = picked && data.detail.find((t) => t.tyre.position === picked);

  return (
    <div className="vehicle-tyres">
      {showTitle && (
        <>
          <h2>{data.vehicle.split(' ')[0]}</h2>
          <p className="muted">{data.vehicle.slice(data.vehicle.indexOf('(') + 1, -1)} · {data.tyres} tyres · <TyreStatus status={data.status} /></p>
        </>
      )}
      <div className="tyre-facts">
        <span><strong>{data.wearParticlesKgPerWeek} kg</strong> wear particles / week</span>
        <span><strong>{data.extraCo2KgPerWeek} kg</strong> extra CO₂ / week</span>
        <span><strong>{rupees(data.plannedCostInr)}</strong> planned (90 days)</span>
      </div>

      <div className="axles" aria-label="Tyre layout, front at the top">
        <div className="axle-front">Front</div>
        {rows.map((row) => (
          <div key={row.name} className="axle-row">
            <div className="axle-side">{row.left.map((tv) => <TyreChip key={tv.tyre.position} tv={tv} active={picked === tv.tyre.position} onPick={setPicked} />)}</div>
            <div className="axle-bar"><span>{row.name}</span></div>
            <div className="axle-side">{row.right.map((tv) => <TyreChip key={tv.tyre.position} tv={tv} active={picked === tv.tyre.position} onPick={setPicked} />)}</div>
          </div>
        ))}
        <div className="legend">
          {['REPLACE_NOW', 'DUE_30', 'DUE_90', 'OK'].map((k) => <span key={k}><i className={`dot ${STATUS[k].cls}`} />{STATUS[k].label}</span>)}
          <span><i className="dot flagged" />Pressure / wear / heat flag</span>
        </div>
      </div>

      {pick && (
        <div className="tyre-detail">
          <strong>{pick.tyre.position}</strong> · {pick.tyre.brand} {pick.tyre.size} · {pick.tyre.serial}
          <dl className="facts">
            <dt>Tread</dt><dd>{pick.tyre.treadMm} of {pick.tyre.treadNewMm} mm ({pick.treadLeftPct}% usable left; limit {pick.removalMm} mm)</dd>
            <dt>Life left</dt><dd>{pick.remainingKm.toLocaleString('en-IN')} km{pick.replaceBy ? ` · replace by ${pick.replaceBy}` : ''}</dd>
            <dt>Wear rate</dt><dd>{pick.wearPer1000Km} mm per 1,000 km · {pick.wearParticlesGPerKm} g particles/km</dd>
            <dt>Pressure</dt><dd>{pick.tyre.pressurePsi} psi (spec {pick.tyre.recommendedPsi}, {pick.pressureDeltaPct > 0 ? '+' : ''}{pick.pressureDeltaPct}%)</dd>
            <dt>Temperature</dt><dd>{pick.tyre.temperatureC} °C</dd>
            <dt>History</dt><dd>Fitted {pick.tyre.fitted} · {pick.tyre.kmRun.toLocaleString('en-IN')} km · {pick.tyre.retreads} retread{pick.tyre.retreads === 1 ? '' : 's'}</dd>
            <dt>Prices</dt><dd>New {rupees(pick.tyre.newPriceInr)}{pick.tyre.retreadPriceInr ? ` · retread ${rupees(pick.tyre.retreadPriceInr)}` : ' · not retreadable'}</dd>
          </dl>
          {pick.flags.length > 0 && <ul className="plain">{pick.flags.map((f) => <li key={f}><span className="sev MEDIUM">flag</span> {f}</li>)}</ul>}
        </div>
      )}
      {!pick && <p className="fine-print">Select a tyre for its details.</p>}

      <h3>Suggested actions</h3>
      {data.recommendations.length === 0 ? <p className="muted">None. All tyres are within limits.</p> : (
        <ul className="plain">
          {data.recommendations.map((r, i) => (
            <li key={i}>
              <span className={`sev ${r.urgency}`}>{r.urgency.toLowerCase()}</span> <strong>{r.action}</strong> ({r.position}): {r.costInr ? rupees(r.costInr) : 'no cost'}
              {r.monthlySavingInr > 0 ? `, saves ≈${rupees(r.monthlySavingInr)}/month` : ''}. <span className="muted">{r.reason}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function TyreChip({ tv, active, onPick }) {
  const flagged = tv.flags.some((f) => !f.startsWith('Tread'));
  return (
    <button type="button" className={`tyre-chip ${STATUS[tv.status].cls}${flagged ? ' flagged' : ''}${active ? ' active' : ''}`}
      onClick={() => onPick(active ? null : tv.tyre.position)} title={`${tv.tyre.position}: ${tv.tyre.treadMm} mm, ${tv.tyre.pressurePsi} psi`}>
      <strong>{tv.tyre.treadMm}</strong><small>mm</small>
    </button>
  );
}

function TyreStatus({ status }) {
  const s = STATUS[status] || STATUS.OK;
  return <span className={`tyre-status ${s.cls}`}>{s.label}</span>;
}

function Kpi({ label, value, alert, warn }) {
  return <div className={alert ? 'kpi alert' : warn ? 'kpi warn' : 'kpi'}><span>{label}</span><strong>{value}</strong></div>;
}

function Metric({ value, label, hint }) {
  return (
    <div className="metric">
      <strong>{value}</strong>
      <span>{label}</span>
      {hint && <small>{hint}</small>}
    </div>
  );
}

/** Rows of the axle diagram: steer first, then each drive axle; outer tyres on the outside. */
function groupAxles(views) {
  const byAxle = new Map();
  for (const tv of views) {
    const name = tv.tyre.position.replace(/ (L|R)( (outer|inner))?$/, '');
    if (!byAxle.has(name)) byAxle.set(name, []);
    byAxle.get(name).push(tv);
  }
  const order = (p) => (p.includes('outer') ? 0 : 1);
  return [...byAxle.entries()].map(([name, list]) => ({
    name,
    left: list.filter((t) => / L( |$)/.test(t.tyre.position)).sort((a, b) => order(a.tyre.position) - order(b.tyre.position)),
    right: list.filter((t) => / R( |$)/.test(t.tyre.position)).sort((a, b) => order(b.tyre.position) - order(a.tyre.position)),
  }));
}
