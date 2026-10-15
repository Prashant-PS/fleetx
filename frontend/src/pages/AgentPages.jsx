import { useEffect, useState } from 'react';
import { api, formatDateTime } from '../api.js';
import { useChat } from '../chat/ChatContext.jsx';
import RichText from '../RichText.jsx';
import { Link, navigate } from '../router.jsx';
import Icon from '../ui/Icon.jsx';
import { AgentCard } from './HomePage.jsx';

export function AgentsPage() {
  const [agents, setAgents] = useState(null);
  useEffect(() => {
    api.get('/api/agents').then(setAgents).catch(() => setAgents([]));
  }, []);
  return (
    <div className="page">
      <PageHead title="AI agents" subtitle="Specialised assistants that check your fleet continuously. Open one to see its findings." />
      <div className="agent-grid wide">
        {(agents || []).map((a) => <AgentCard key={a.id} agent={a} />)}
      </div>
      <p className="fine-print">Want a different mix of checks? <Link to="/custom-agents/new">Build a custom agent</Link>.</p>
    </div>
  );
}

export function AgentDetailPage({ id }) {
  const [agent, setAgent] = useState(null);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');

  const run = () => {
    setResult(null);
    api.post(`/api/agents/${id}/run`).then(setResult).catch((e) => setError(e.message));
  };

  useEffect(() => {
    api.get('/api/agents').then((list) => setAgent(list.find((a) => a.id === id) || null));
    run();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  return (
    <div className="page">
      <Link to="/agents" className="back">← All agents</Link>
      <PageHead
        icon={agent?.icon}
        title={agent?.name || 'Agent'}
        subtitle={agent?.description}
      />
      {error && <div className="error">{error}</div>}
      <AgentResults result={result} onRerun={run} scheduleTask={{ taskType: 'agent', taskRef: id }} />
    </div>
  );
}

/** Summary, findings (resolvable) or report, plus save / schedule / ask-AI tools. Shared with custom agents. */
export function AgentResults({ result, onRerun, scheduleTask }) {
  const { reset, ask } = useChat();
  const [filter, setFilter] = useState('ALL');
  const [resolved, setResolved] = useState(new Set());
  const [note, setNote] = useState('');
  const [scheduling, setScheduling] = useState(false);

  if (!result) return <div className="loading"><span className="spinner" /> Running checks…</div>;

  const findings = result.findings.filter((f) => !resolved.has(f.id) && (filter === 'ALL' || f.severity === filter));
  const isReport = result.findings.length === 0 && result.markdown.startsWith('#');

  const resolve = async (f) => {
    await api.post(`/api/findings/${encodeURIComponent(f.id)}/resolve`);
    setResolved(new Set([...resolved, f.id]));
  };

  const save = async () => {
    const item = await api.post('/api/saved', {
      kind: 'agent-run', title: `${result.name} · ${new Date().toLocaleDateString()}`,
      content: `_${result.summary}_\n\n${result.markdown}`, source: `agent:${result.agentId}`,
    });
    setNote(<>Saved. <Link to={`/saved/${item.id}`}>Open</Link></>);
  };

  const askAi = (prompt) => {
    reset();
    ask(prompt, 'action');
    navigate('/chat');
  };

  return (
    <>
      <div className="result-bar">
        <div>
          <div className="result-summary">{result.summary}</div>
          <div className="fine-print">Checked {formatDateTime(result.generatedAt)}</div>
        </div>
        <div className="result-tools">
          <button type="button" className="btn" onClick={onRerun}><Icon name="play" size={16} /> Run again</button>
          <button type="button" className="btn" onClick={save}><Icon name="bookmark" size={16} /> Save</button>
          <button type="button" className="btn" onClick={() => setScheduling(!scheduling)}><Icon name="calendar" size={16} /> Schedule</button>
          <button type="button" className="btn primary" onClick={() => askAi(`Review the ${result.name} results and tell me what to do first.`)}>
            <Icon name="sparkle" size={16} /> Ask AI+
          </button>
        </div>
      </div>
      {note && <div className="note">{note}</div>}
      {scheduling && (
        <ScheduleForm
          preset={{ ...scheduleTask, title: `${result.name} check` }}
          onDone={(s) => {
            setScheduling(false);
            setNote(<>Scheduled "{s.title}". <Link to="/schedules">View schedules</Link></>);
          }}
        />
      )}

      {isReport ? (
        <div className="report"><RichText text={result.markdown} /></div>
      ) : (
        <>
          {result.findings.length > 0 && (
            <div className="filter-chips">
              {['ALL', 'HIGH', 'MEDIUM', 'LOW'].map((s) => (
                <button key={s} type="button" className={filter === s ? 'chip active' : 'chip'} onClick={() => setFilter(s)}>
                  {s === 'ALL' ? `All (${result.findings.length - resolved.size})` : `${s.toLowerCase()} (${result.counts?.[s] ?? 0})`}
                </button>
              ))}
            </div>
          )}
          {result.markdown.startsWith('>') && <div className="instructions"><RichText text={result.markdown.split('\n')[0]} /></div>}
          {findings.length === 0 && <p className="empty">Nothing to show. {result.findings.length ? 'Try another filter.' : 'All clear.'}</p>}
          <ul className="findings">
            {findings.map((f) => (
              <li key={f.id} className="finding">
                <span className={`sev ${f.severity}`}>{f.severity.toLowerCase()}</span>
                <div className="finding-main">
                  <div className="finding-title">{f.title}</div>
                  <Link to={`/fleet?v=${f.vehicleId}`} className="finding-vehicle">{f.vehicle}</Link>
                  <div className="finding-detail">{f.detail}</div>
                  <div className="finding-action"><Icon name="arrow" size={14} /> {f.action}</div>
                </div>
                <div className="finding-buttons">
                  <button type="button" className="btn small" onClick={() => resolve(f)}><Icon name="check" size={14} /> Resolve</button>
                  <button type="button" className="btn small ghost" onClick={() => askAi(`Help me fix this: ${f.title} on ${f.vehicle}. ${f.detail}`)}>
                    Ask AI+
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

/** Inline form that creates a schedule; preset fixes the task. */
export function ScheduleForm({ preset, onDone, agents, customAgents }) {
  const [form, setForm] = useState({
    title: preset?.title || '', taskType: preset?.taskType || 'agent', taskRef: preset?.taskRef || '',
    message: '', cadence: preset?.taskType === 'reminder' ? 'ONCE' : 'WEEKLY', time: '08:00', dayOfWeek: 'MONDAY',
    date: new Date(Date.now() + 86400000).toISOString().slice(0, 10),
  });
  const [error, setError] = useState('');
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  const fixedTask = Boolean(preset?.taskRef);

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    try {
      const s = await api.post('/api/schedules', {
        ...form,
        taskRef: form.taskType === 'reminder' ? null : form.taskRef,
        dayOfWeek: form.cadence === 'WEEKLY' ? form.dayOfWeek : null,
        date: form.cadence === 'ONCE' ? form.date : null,
      });
      onDone(s);
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <form className="form-card" onSubmit={submit}>
      <div className="form-grid">
        <label>Title<input value={form.title} onChange={set('title')} placeholder="e.g. Monday compliance check" /></label>
        {!fixedTask && (
          <label>Task
            <select value={form.taskType} onChange={(e) => setForm({ ...form, taskType: e.target.value, taskRef: '',
              cadence: e.target.value === 'reminder' ? 'ONCE' : form.cadence })}>
              <option value="agent">Run an AI agent</option>
              <option value="custom-agent">Run a custom agent</option>
              <option value="reminder">Reminder</option>
            </select>
          </label>
        )}
        {!fixedTask && form.taskType === 'agent' && (
          <label>Agent
            <select value={form.taskRef} onChange={set('taskRef')} required>
              <option value="">Choose…</option>
              <option value="attention">Needs attention today (all agents)</option>
              <option value="uptime">Uptime report</option>
              {(agents || []).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </label>
        )}
        {!fixedTask && form.taskType === 'custom-agent' && (
          <label>Custom agent
            <select value={form.taskRef} onChange={set('taskRef')} required>
              <option value="">Choose…</option>
              {(customAgents || []).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </label>
        )}
        {form.taskType === 'reminder' && (
          <label className="span-2">Message<input value={form.message} onChange={set('message')} placeholder="What should I remind you about?" /></label>
        )}
        <label>Repeat
          <select value={form.cadence} onChange={set('cadence')}>
            <option value="ONCE">Once</option>
            <option value="DAILY">Every day</option>
            <option value="WEEKLY">Every week</option>
            <option value="MONTHLY">Every month (1st)</option>
          </select>
        </label>
        {form.cadence === 'WEEKLY' && (
          <label>Day
            <select value={form.dayOfWeek} onChange={set('dayOfWeek')}>
              {DAYS.map((d) => <option key={d} value={d}>{d[0] + d.slice(1).toLowerCase()}</option>)}
            </select>
          </label>
        )}
        {form.cadence === 'ONCE' && <label>Date<input type="date" value={form.date} onChange={set('date')} /></label>}
        <label>Time<input type="time" value={form.time} onChange={set('time')} /></label>
      </div>
      {error && <div className="form-error">{error}</div>}
      <div className="form-buttons"><button type="submit" className="btn primary">Create schedule</button></div>
    </form>
  );
}

export function PageHead({ icon, title, subtitle, children }) {
  return (
    <header className="page-head">
      {icon && <span className={`agent-icon ${icon} big`}><Icon name={icon} size={26} /></span>}
      <div className="page-head-text">
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {children && <div className="page-head-actions">{children}</div>}
    </header>
  );
}
