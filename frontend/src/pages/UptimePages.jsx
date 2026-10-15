import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useChat } from '../chat/ChatContext.jsx';
import { Link, navigate } from '../router.jsx';
import Icon from '../ui/Icon.jsx';
import { PageHead, ScheduleForm } from './AgentPages.jsx';
import { rupees } from './TyrePages.jsx';

const WINDOWS = [7, 30, 90];

export function UptimePage() {
  const [days, setDays] = useState(30);
  const [data, setData] = useState(null);
  const [scheduling, setScheduling] = useState(false);
  const [note, setNote] = useState('');
  const { reset, ask } = useChat();

  useEffect(() => {
    setData(null);
    api.get(`/api/uptime?days=${days}`).then(setData);
  }, [days]);

  const askAi = (q) => {
    reset();
    ask(q, 'analysis');
    navigate('/chat');
  };

  return (
    <div className="page wide">
      <PageHead icon="uptime" title="Uptime"
        subtitle="Availability, downtime, the Uptime package KPIs and the OEM uptime services available to your fleet (demo history).">
        <div className="segmented" role="radiogroup" aria-label="Period">
          {WINDOWS.map((w) => (
            <button key={w} type="button" role="radio" aria-checked={days === w} className={days === w ? 'active' : ''} onClick={() => setDays(w)}>
              {w} days
            </button>
          ))}
        </div>
        <button type="button" className="btn" onClick={() => setScheduling(!scheduling)}><Icon name="calendar" size={16} /> Weekly report</button>
        <button type="button" className="btn primary" onClick={() => askAi('How is my fleet uptime and what should I do to improve it?')}>
          <Icon name="sparkle" size={16} /> Ask AI+
        </button>
      </PageHead>
      {scheduling && (
        <ScheduleForm preset={{ taskType: 'agent', taskRef: 'uptime', title: 'Weekly uptime report' }}
          onDone={() => { setScheduling(false); setNote('Weekly uptime report scheduled.'); }} />
      )}
      {note && <div className="note">{note}</div>}
      {!data ? <div className="loading"><span className="spinner" /> Calculating uptime…</div> : <UptimeBody data={data} askAi={askAi} />}
    </div>
  );
}

function UptimeBody({ data, askAi }) {
  const k = data.kpis;
  const maxCause = Math.max(...data.causes.map((c) => c.hours), 1);
  const minWeek = Math.min(...data.weeklyTrend.map((w) => w.uptimePct), 99);
  const floor = Math.max(0, Math.floor(minWeek - 2));

  return (
    <>
      <div className="kpis">
        <Kpi label={`Uptime, ${data.windowDays} days`} value={`${k.uptimePct}%`} good={k.uptimePct >= 97} />
        <Kpi label="Available now" value={`${k.availableNow} / ${k.vehicles}`} alert={k.availableNow < k.vehicles} />
        <Kpi label="Unplanned stops / 100,000 km" value={k.unplannedStopsPer100kKm} />
        <Kpi label="Mean time to triage" value={`${k.meanTriageHours} h`} />
        <Kpi label="Closure rate (≤ 48 h)" value={`${k.closureRatePct}%`} />
        <Kpi label="Km between failures" value={k.mtbfKm.toLocaleString('en-IN')} />
        <Kpi label="Mean time to repair" value={`${k.mttrHours} h`} />
        <Kpi label="Downtime cost" value={rupees(k.downtimeCostInr)} />
      </div>

      <div className="tyre-panels">
        <section className="panel">
          <h2><Icon name="analysis" size={18} /> Weekly uptime <small>scale {floor}–100%</small></h2>
          <div className="trend" role="img" aria-label={`Weekly uptime from ${data.weeklyTrend[0]?.uptimePct}% to ${data.weeklyTrend.at(-1)?.uptimePct}%`}>
            {data.weeklyTrend.map((w) => (
              <div key={w.weekOf} className="trend-col" title={`Week of ${w.weekOf}: ${w.uptimePct}%`}>
                <span className="trend-val">{w.uptimePct}</span>
                <span className="trend-bar" style={{ height: `${Math.max(4, ((w.uptimePct - floor) / (100 - floor)) * 100)}%` }} />
                <span className="trend-label">{w.weekOf}</span>
              </div>
            ))}
          </div>
        </section>
        <section className="panel">
          <h2><Icon name="operations" size={18} /> Downtime by cause <small>{Math.round(k.unplannedHours)} h unplanned, {Math.round(k.plannedHours)} h planned</small></h2>
          <ul className="hbars">
            {data.causes.map((c) => (
              <li key={c.cause}>
                <span className="hbar-label">{c.cause}</span>
                <span className="hbar-track"><span className={c.cause === 'Service' ? 'hbar planned' : 'hbar'} style={{ width: `${(c.hours / maxCause) * 100}%` }} /></span>
                <span className="hbar-val">{Math.round(c.hours)} h · {c.pct}%</span>
              </li>
            ))}
          </ul>
        </section>
      </div>

      {data.offRoadNow.length > 0 && (
        <section className="section">
          <div className="section-head"><h2>Off the road now <span>{data.offRoadNow.length} vehicle{data.offRoadNow.length === 1 ? '' : 's'}</span></h2></div>
          <ul className="findings">
            {data.offRoadNow.map((o) => (
              <li key={o.vehicleId} className="finding">
                <span className="sev HIGH">down</span>
                <div className="finding-main">
                  <div className="finding-title">{o.description}</div>
                  <Link to={`/fleet?v=${o.vehicleId}`} className="finding-vehicle">{o.vehicle}</Link>
                  <div className="finding-detail">{o.location} · down for {Math.round(o.hours)} h · costs {rupees(o.lostRevenuePerDayInr)} per day</div>
                </div>
                <div className="finding-buttons">
                  <button type="button" className="btn small ghost" onClick={() => askAi(`${o.vehicle} is off the road: ${o.description}. How do I get it back fastest?`)}>Ask AI+</button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="section">
        <div className="section-head"><h2>How to raise uptime <span>estimated savings</span></h2></div>
        <div className="ideas">
          {data.improvements.map((i) => (
            <div key={i.title} className="idea">
              <strong>{i.title}</strong>
              <p>{i.detail}</p>
              {i.hoursSavedPerMonth > 0 && <div className="saving">≈{Math.round(i.hoursSavedPerMonth)} vehicle-hours and {rupees(i.valuePerMonthInr)} a month</div>}
            </div>
          ))}
        </div>
      </section>

      <div className="tyre-panels">
        <section className="panel">
          <h2>Lowest uptime</h2>
          <table className="data-table compact">
            <tbody>
              {data.worstVehicles.map((w) => (
                <tr key={w.vehicleId} onClick={() => navigate(`/fleet?v=${w.vehicleId}`)} style={{ cursor: 'pointer' }}>
                  <td>{w.vehicle}</td><td><strong>{w.uptimePct}%</strong></td>
                  <td className="muted">{Math.round(w.downtimeHours)} h · {w.unplannedStops} breakdown{w.unplannedStops === 1 ? '' : 's'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <section className="panel">
          <h2>At risk of the next breakdown</h2>
          {data.atRisk.length === 0 ? <p className="muted">No vehicle above the risk threshold.</p> : (
            <ul className="plain">
              {data.atRisk.map((r) => (
                <li key={r.vehicleId}>
                  <span className={`sev ${r.score >= 50 ? 'HIGH' : 'MEDIUM'}`}>{r.score}</span>{' '}
                  <Link to={`/fleet?v=${r.vehicleId}`}>{r.vehicle}</Link>
                  <div className="muted">{r.reasons.join('; ')}</div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section className="section">
        <div className="section-head">
          <h2>OEM uptime features for your fleet <span>Workbook theme "Uptime &amp; remote service"</span></h2>
          <button type="button" className="see-all as-button" onClick={() => askAi('Which uptime features do my OEMs offer, and which should I switch on first?')}>
            Ask AI+ which to use <Icon name="arrow" size={16} />
          </button>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Feature</th><th>Priority</th>
                {Object.keys(data.features[0]?.statusByFleetOem || {}).map((o) => <th key={o}>{o}</th>)}
                <th>Vehicles covered</th>
              </tr>
            </thead>
            <tbody>
              {data.features.map((f) => (
                <tr key={f.id} title={f.definition}>
                  <td><strong>{f.id}</strong> {f.feature}<div className="muted">{f.definition}</div></td>
                  <td>{f.priority}</td>
                  {Object.entries(f.statusByFleetOem).map(([o, s]) => <td key={o}><span className={`status st-${s.toLowerCase()}`}>{s}</span></td>)}
                  <td>{f.vehiclesCovered} of {f.vehiclesInFleet}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="fine-print">D documented · P partner · NV not verified (not the same as absent). BharatBenz = Daimler Truck. Eicher, Switch Mobility and EKA are not in the workbook.</p>
      </section>

      <details className="assumptions">
        <summary>How these numbers are calculated</summary>
        <ul>{data.assumptions.map((a) => <li key={a}>{a}</li>)}</ul>
      </details>
    </>
  );
}

/** Uptime and downtime history for one vehicle (used in the My fleet drawer). */
export function VehicleUptime({ id }) {
  const [data, setData] = useState(null);
  useEffect(() => {
    setData(null);
    api.get(`/api/uptime/vehicles/${id}?days=90`).then(setData).catch(() => setData({ error: true }));
  }, [id]);
  if (!data) return <div className="loading"><span className="spinner" /> Loading…</div>;
  if (data.error) return <p className="muted">No uptime data.</p>;
  return (
    <div>
      <p><strong>{data.uptimePct}% uptime</strong> over 90 days · {Math.round(data.downtimeHours)} h down · {data.unplannedStops} breakdown{data.unplannedStops === 1 ? '' : 's'}, {data.plannedStops} service{data.plannedStops === 1 ? '' : 's'} · {rupees(data.costInr)} incl. lost revenue</p>
      {data.events.length > 0 && (
        <table className="data-table compact">
          <tbody>
            {data.events.map((e) => (
              <tr key={e.id}>
                <td>{new Date(e.start).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}</td>
                <td><span className={`sev ${e.type === 'UNPLANNED' ? 'HIGH' : 'LOW'}`}>{e.type === 'UNPLANNED' ? 'breakdown' : 'service'}</span></td>
                <td>{e.description}<div className="muted">{e.location}</div></td>
                <td>{e.end ? `${Math.round((new Date(e.end) - new Date(e.start)) / 36e5)} h` : 'ongoing'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function Kpi({ label, value, alert, good }) {
  return <div className={alert ? 'kpi warn' : good ? 'kpi good' : 'kpi'}><span>{label}</span><strong>{value}</strong></div>;
}
