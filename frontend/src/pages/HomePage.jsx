import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useChat } from '../chat/ChatContext.jsx';
import PromptBox from '../chat/PromptBox.jsx';
import { Link, navigate } from '../router.jsx';
import Icon from '../ui/Icon.jsx';

const TRY_ASKING = [
  'What needs my attention today?',
  'Show vehicles at risk of downtime',
  'Create a weekly fleet report',
  'Remind me of expiring contracts',
  'What is my fleet tire status?',
  'What is my fleet uptime?',
];

export default function HomePage({ userName, aiEnabled }) {
  const { ask, reset } = useChat();
  const [agents, setAgents] = useState(null);
  const [custom, setCustom] = useState([]);
  const [counts, setCounts] = useState({ schedules: 0, saved: 0 });

  useEffect(() => {
    api.get('/api/agents').then(setAgents).catch(() => setAgents([]));
    api.get('/api/custom-agents').then(setCustom).catch(() => {});
    Promise.all([api.get('/api/schedules'), api.get('/api/saved')])
      .then(([s, v]) => setCounts({ schedules: s.filter((x) => x.enabled).length, saved: v.length }))
      .catch(() => {});
  }, []);

  const start = (question, mode = '') => {
    reset();
    ask(question, mode);
    navigate('/chat');
  };

  const firstName = (userName || '').trim().split(/\s+/)[0];

  return (
    <div className="home-ai">
      <section className="hero">
        <div className="hero-text">
          <h1>
            {firstName ? `Hi ${firstName},` : 'Hi there,'}
            <span>how can I help with your fleet today?</span>
          </h1>
        </div>
        <div className="hero-art" aria-hidden>
          <img src="/fleet-bharat-emblem.png" alt="" className="hero-emblem" width="150" height="150" />
          <div className="hero-motto">Less firefighting.<br />More uptime.<br />Better decisions.</div>
        </div>
      </section>

      <PromptBox autoFocus onSubmit={start} />
      {!aiEnabled && (
        <p className="fine-print center">
          Running without an AI key: questions are answered by the built-in agents. Set ANTHROPIC_API_KEY on the server for
          full AI answers.
        </p>
      )}

      <div className="try-asking">
        <span>Try asking</span>
        {TRY_ASKING.map((q) => (
          <button key={q} type="button" className="chip" onClick={() => start(q)}>{q}</button>
        ))}
      </div>

      <section className="section">
        <div className="section-head">
          <h2>Your AI agents <span>Specialised assistants, ready to help.</span></h2>
          <Link to="/agents" className="see-all">View all agents <Icon name="arrow" size={16} /></Link>
        </div>
        <div className="agent-grid">
          {(agents || Array.from({ length: 5 }, (_, i) => ({ id: i }))).map((a) =>
            a.name ? <AgentCard key={a.id} agent={a} /> : <div key={a.id} className="agent-card skeleton" />
          )}
        </div>
      </section>

      <section className="section">
        <div className="section-head">
          <h2>Your custom agents <span>Build agents for your own workflows.</span></h2>
          <Link to="/custom-agents" className="see-all">View all custom agents <Icon name="arrow" size={16} /></Link>
        </div>
        <div className="custom-row">
          <Link to="/custom-agents/new" className="create-card">
            <span className="create-plus"><Icon name="plus" size={26} /></span>
            <span>
              <strong>{custom.length ? 'Create a custom agent' : 'Create your first custom agent'}</strong>
              <small>Automate your own checks, tailored to your fleet.</small>
            </span>
          </Link>
          {custom.slice(0, 3).map((c) => (
            <Link key={c.id} to={`/custom-agents/${c.id}`} className="agent-card">
              <span className="agent-icon custom"><Icon name="custom" /></span>
              <span className="agent-text"><strong>{c.name}</strong><small>{c.description || `${c.checks.length} checks`}</small></span>
              <Icon name="arrow" size={16} className="agent-arrow" />
            </Link>
          ))}
        </div>
      </section>

      <section className="quick-row">
        <QuickCard to="/schedules" icon="calendar" title="My schedules" text={`${counts.schedules} active · automated reports and tasks`} />
        <QuickCard to="/saved" icon="bookmark" title="Saved" text={`${counts.saved} item${counts.saved === 1 ? '' : 's'} · your important insights and chats`} />
        <QuickCard to="/apps" icon="apps" title="Fleet apps" text="Quick access to all Fleet Bharat applications" />
      </section>
    </div>
  );
}

export function AgentCard({ agent }) {
  const high = agent.openIssues?.HIGH || 0;
  const total = Object.values(agent.openIssues || {}).reduce((a, b) => a + b, 0);
  return (
    <Link to={`/agents/${agent.id}`} className="agent-card">
      <span className={`agent-icon ${agent.icon}`}><Icon name={agent.icon} /></span>
      <span className="agent-text">
        <strong>{agent.name}</strong>
        <small>{agent.description}</small>
        {total > 0 && (
          <span className="agent-badges">
            {high > 0 && <span className="sev HIGH">{high} high</span>}
            <span className="sev-muted">{total} open</span>
          </span>
        )}
      </span>
      <Icon name="arrow" size={16} className="agent-arrow" />
    </Link>
  );
}

function QuickCard({ to, icon, title, text }) {
  return (
    <Link to={to} className="quick-card">
      <span className="quick-icon"><Icon name={icon} /></span>
      <span className="agent-text"><strong>{title}</strong><small>{text}</small></span>
      <Icon name="arrow" size={16} className="agent-arrow" />
    </Link>
  );
}
