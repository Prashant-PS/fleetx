import { useEffect, useState } from 'react';
import { api, formatDateTime } from '../api.js';
import { useChat } from '../chat/ChatContext.jsx';
import RichText from '../RichText.jsx';
import { Link, navigate } from '../router.jsx';
import Icon from '../ui/Icon.jsx';
import { PageHead, ScheduleForm } from './AgentPages.jsx';

const KIND_LABEL = { chat: 'Chat answer', report: 'Report', 'agent-run': 'Agent results', 'scheduled-report': 'Scheduled report' };

export function SavedPage() {
  const [items, setItems] = useState(null);
  useEffect(() => {
    api.get('/api/saved').then(setItems).catch(() => setItems([]));
  }, []);
  return (
    <div className="page">
      <PageHead title="Saved" subtitle="Answers, reports and agent results you kept, plus everything your schedules produced." />
      {items && items.length === 0 && (
        <p className="empty">Nothing saved yet. Use the bookmark under any AI+ answer, or Save on an agent's results.</p>
      )}
      <div className="list">
        {(items || []).map((s) => (
          <Link key={s.id} to={`/saved/${s.id}`} className="list-row link">
            <span className="agent-icon reporting"><Icon name={s.kind === 'chat' ? 'chat' : 'report'} /></span>
            <div className="list-main">
              <div className="list-title">{s.title}</div>
              <div className="list-sub">{KIND_LABEL[s.kind] || s.kind} · {formatDateTime(s.createdAt)}</div>
            </div>
            <Icon name="arrow" size={16} className="agent-arrow" />
          </Link>
        ))}
      </div>
    </div>
  );
}

export function SavedItemPage({ id }) {
  const [item, setItem] = useState(null);
  const [error, setError] = useState('');
  const { reset, ask } = useChat();
  useEffect(() => {
    api.get(`/api/saved/${id}`).then(setItem).catch(() => setError('This item no longer exists.'));
  }, [id]);

  const remove = async () => {
    if (!window.confirm('Delete this saved item?')) return;
    await api.del(`/api/saved/${id}`);
    navigate('/saved');
  };

  return (
    <div className="page">
      <Link to="/saved" className="back">← Saved</Link>
      {error && <p className="empty">{error}</p>}
      {item && (
        <>
          <PageHead title={item.title} subtitle={`${KIND_LABEL[item.kind] || item.kind} · ${formatDateTime(item.createdAt)}`}>
            <button type="button" className="btn" onClick={() => navigator.clipboard?.writeText(item.content)}><Icon name="copy" size={16} /> Copy</button>
            <button type="button" className="btn" onClick={() => window.print()}><Icon name="report" size={16} /> Print</button>
            <button type="button" className="btn primary" onClick={() => {
              reset();
              ask(`Here is a saved item titled "${item.title}". Summarise what matters and what I should do next:\n\n${item.content.slice(0, 6000)}`, 'analysis');
              navigate('/chat');
            }}><Icon name="sparkle" size={16} /> Ask AI+</button>
            <button type="button" className="icon-btn" onClick={remove} aria-label="Delete"><Icon name="trash" /></button>
          </PageHead>
          <div className="report"><RichText text={item.content} /></div>
        </>
      )}
    </div>
  );
}

export function SchedulesPage() {
  const [list, setList] = useState(null);
  const [agents, setAgents] = useState([]);
  const [custom, setCustom] = useState([]);
  const [creating, setCreating] = useState(false);
  const [note, setNote] = useState('');

  const load = () => api.get('/api/schedules').then(setList).catch(() => setList([]));
  useEffect(() => {
    load();
    api.get('/api/agents').then(setAgents);
    api.get('/api/custom-agents').then(setCustom);
  }, []);

  const toggle = async (s) => {
    try {
      await api.patch(`/api/schedules/${s.id}`, { enabled: !s.enabled });
      load();
    } catch (e) {
      setNote(e.message);
    }
  };
  const runNow = async (s) => {
    setNote(`Running "${s.title}"…`);
    const r = await api.post(`/api/schedules/${s.id}/run`);
    setNote(`"${r.title}": ${r.lastSummary}`);
    load();
  };
  const remove = async (s) => {
    if (!window.confirm(`Delete the schedule "${s.title}"?`)) return;
    await api.del(`/api/schedules/${s.id}`);
    load();
  };

  return (
    <div className="page">
      <PageHead title="My schedules" subtitle="Agents and reminders that run automatically. Results arrive as notifications and in Saved.">
        <button type="button" className="btn primary" onClick={() => setCreating(!creating)}><Icon name="plus" size={16} /> New schedule</button>
      </PageHead>
      {creating && (
        <ScheduleForm agents={agents} customAgents={custom} onDone={(s) => {
          setCreating(false);
          setNote(`Created "${s.title}".`);
          load();
        }} />
      )}
      {note && <div className="note">{note}</div>}
      {list && list.length === 0 && <p className="empty">No schedules yet.</p>}
      <div className="list">
        {(list || []).map((s) => (
          <div key={s.id} className={s.enabled ? 'list-row' : 'list-row disabled'}>
            <span className="agent-icon operations"><Icon name={s.taskType === 'reminder' ? 'bell' : 'calendar'} /></span>
            <div className="list-main">
              <div className="list-title">{s.title}</div>
              <div className="list-sub">
                {describe(s, agents, custom)} · {s.enabled && s.nextRun ? `next ${formatDateTime(s.nextRun)}` : 'paused'}
              </div>
              {s.lastRun && <div className="list-sub">Last run {formatDateTime(s.lastRun)}: {s.lastSummary}</div>}
            </div>
            <div className="list-buttons">
              <label className="switch" title={s.enabled ? 'Pause' : 'Resume'}>
                <input type="checkbox" checked={s.enabled} onChange={() => toggle(s)} />
                <span />
              </label>
              <button type="button" className="btn small" onClick={() => runNow(s)}><Icon name="play" size={14} /> Run now</button>
              <button type="button" className="icon-btn small" onClick={() => remove(s)} aria-label="Delete"><Icon name="trash" size={16} /></button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function describe(s, agents, custom) {
  const when = {
    ONCE: `On ${s.date} at ${s.time}`,
    DAILY: `Every day at ${s.time}`,
    WEEKLY: `Every ${s.dayOfWeek?.[0]}${s.dayOfWeek?.slice(1).toLowerCase()} at ${s.time}`,
    MONTHLY: `On the 1st of each month at ${s.time}`,
  }[s.cadence];
  const what = s.taskType === 'reminder' ? 'Reminder'
    : s.taskType === 'custom-agent' ? custom.find((c) => c.id === s.taskRef)?.name || 'Custom agent'
      : s.taskRef === 'attention' ? 'Needs attention (all agents)' : s.taskRef === 'uptime' ? 'Uptime report' : agents.find((a) => a.id === s.taskRef)?.name || s.taskRef;
  return `${what} · ${when}`;
}
