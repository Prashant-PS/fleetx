import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { Link, navigate } from '../router.jsx';
import Icon from '../ui/Icon.jsx';
import { AgentResults, PageHead } from './AgentPages.jsx';

export function CustomAgentsPage() {
  const [list, setList] = useState(null);
  const load = () => api.get('/api/custom-agents').then(setList).catch(() => setList([]));
  useEffect(() => {
    load();
  }, []);

  const remove = async (a) => {
    if (!window.confirm(`Delete the custom agent "${a.name}"?`)) return;
    await api.del(`/api/custom-agents/${a.id}`);
    load();
  };

  return (
    <div className="page">
      <PageHead title="Custom agents" subtitle="Combine any checks, narrow them to the vehicles you care about, and run or schedule them.">
        <Link to="/custom-agents/new" className="btn primary"><Icon name="plus" size={16} /> New custom agent</Link>
      </PageHead>
      {list && list.length === 0 && (
        <Link to="/custom-agents/new" className="create-card big">
          <span className="create-plus"><Icon name="plus" size={26} /></span>
          <span><strong>Create your first custom agent</strong><small>For example: "Electric buses: charging readiness and expiring documents".</small></span>
        </Link>
      )}
      <div className="list">
        {(list || []).map((a) => (
          <div key={a.id} className="list-row">
            <span className="agent-icon custom"><Icon name="custom" /></span>
            <div className="list-main">
              <Link to={`/custom-agents/${a.id}`} className="list-title">{a.name}</Link>
              <div className="list-sub">{a.description || 'No description'} · {a.checks.length} checks{filterText(a.filter)}</div>
            </div>
            <div className="list-buttons">
              <Link to={`/custom-agents/${a.id}`} className="btn small"><Icon name="play" size={14} /> Run</Link>
              <Link to={`/custom-agents/${a.id}/edit`} className="btn small ghost">Edit</Link>
              <button type="button" className="icon-btn small" onClick={() => remove(a)} aria-label="Delete"><Icon name="trash" size={16} /></button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function CustomAgentRunPage({ id }) {
  const [agent, setAgent] = useState(null);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const run = () => {
    setResult(null);
    api.post(`/api/custom-agents/${id}/run`).then(setResult).catch((e) => setError(e.message));
  };
  useEffect(() => {
    api.get('/api/custom-agents').then((l) => setAgent(l.find((a) => a.id === id) || null));
    run();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);
  return (
    <div className="page">
      <Link to="/custom-agents" className="back">← Custom agents</Link>
      <PageHead icon="custom" title={agent?.name || 'Custom agent'} subtitle={agent?.description}>
        <Link to={`/custom-agents/${id}/edit`} className="btn">Edit</Link>
      </PageHead>
      {error && <div className="error">{error}</div>}
      <AgentResults result={result} onRerun={run} scheduleTask={{ taskType: 'custom-agent', taskRef: id }} />
    </div>
  );
}

export function CustomAgentFormPage({ id }) {
  const [agents, setAgents] = useState([]);
  const [facets, setFacets] = useState({});
  const [form, setForm] = useState({ name: '', description: '', instructions: '', checks: [], filter: {} });
  const [error, setError] = useState('');

  useEffect(() => {
    api.get('/api/agents').then(setAgents);
    api.get('/api/fleet/facets').then(setFacets);
    if (id) {
      api.get('/api/custom-agents').then((l) => {
        const a = l.find((x) => x.id === id);
        if (a) setForm({ ...a, description: a.description || '', instructions: a.instructions || '', filter: a.filter || {} });
      });
    }
  }, [id]);

  const toggle = (check) =>
    setForm({ ...form, checks: form.checks.includes(check) ? form.checks.filter((c) => c !== check) : [...form.checks, check] });

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    try {
      const body = { ...form, filter: Object.fromEntries(Object.entries(form.filter).filter(([, v]) => v)) };
      const saved = id ? await api.put(`/api/custom-agents/${id}`, body) : await api.post('/api/custom-agents', body);
      navigate(`/custom-agents/${saved.id}`);
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div className="page narrow">
      <Link to="/custom-agents" className="back">← Custom agents</Link>
      <PageHead icon="custom" title={id ? 'Edit custom agent' : 'New custom agent'}
        subtitle="Pick the checks to run and, optionally, which vehicles to include." />
      <form className="form-card" onSubmit={submit}>
        <div className="form-grid">
          <label className="span-2">Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}
            placeholder="e.g. Electric bus readiness" required /></label>
          <label className="span-2">Description<input value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="What this agent is for" /></label>
          <label className="span-2">Instructions for AI+ (optional)
            <textarea rows={3} value={form.instructions} onChange={(e) => setForm({ ...form, instructions: e.target.value })}
              placeholder="e.g. Focus on buses leaving before 7am and suggest which ones to swap." />
          </label>
        </div>

        <h3>Checks</h3>
        <div className="checks">
          {agents.map((a) => (
            <fieldset key={a.id}>
              <legend><Icon name={a.icon} size={16} /> {a.name}</legend>
              {a.checks.map((c) => (
                <label key={c.id} className="check">
                  <input type="checkbox" checked={form.checks.includes(c.id)} onChange={() => toggle(c.id)} /> {c.label}
                </label>
              ))}
            </fieldset>
          ))}
        </div>

        <h3>Vehicles</h3>
        <div className="form-grid">
          {['fuel', 'type', 'depot', 'make'].map((k) => (
            <label key={k}>{k === 'make' ? 'Make' : k[0].toUpperCase() + k.slice(1)}
              <select value={form.filter[k] || ''} onChange={(e) => setForm({ ...form, filter: { ...form.filter, [k]: e.target.value } })}>
                <option value="">All</option>
                {(facets[k] || []).map((v) => <option key={v} value={v}>{v}</option>)}
              </select>
            </label>
          ))}
        </div>
        {error && <div className="form-error">{error}</div>}
        <div className="form-buttons">
          <button type="submit" className="btn primary">{id ? 'Save changes' : 'Create agent'}</button>
        </div>
      </form>
    </div>
  );
}

function filterText(f) {
  const parts = Object.values(f || {}).filter(Boolean);
  return parts.length ? ` · ${parts.join(', ')}` : '';
}
