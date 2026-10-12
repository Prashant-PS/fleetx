import { useState } from 'react';
import { api } from '../api.js';
import { Link } from '../router.jsx';
import Icon from '../ui/Icon.jsx';

const ICONS = { create_schedule: 'calendar', create_reminder: 'bell', save_report: 'bookmark', resolve_finding: 'check' };

/** A proposed action the user must confirm before anything happens. */
export default function ActionCard({ action, onChange }) {
  const [busy, setBusy] = useState(false);

  const confirm = async () => {
    setBusy(true);
    try {
      const result = await api.post('/api/actions/execute', { type: action.type, title: action.title, payload: action.payload });
      onChange({ state: 'done', result });
    } catch (e) {
      onChange({ state: 'error', result: { message: e.message } });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={`action-card ${action.state}`}>
      <span className="action-icon"><Icon name={ICONS[action.type] || 'action'} /></span>
      <div className="action-text">
        <div className="action-title">{action.title}</div>
        <div className="action-desc">
          {action.state === 'done' || action.state === 'error' ? action.result?.message : action.description}
          {action.state === 'done' && action.result?.link && (
            <> <Link to={action.result.link}>View</Link></>
          )}
        </div>
      </div>
      {action.state === 'pending' && (
        <div className="action-buttons">
          <button type="button" className="btn primary" onClick={confirm} disabled={busy}>
            {busy ? 'Working…' : 'Confirm'}
          </button>
          <button type="button" className="btn ghost" onClick={() => onChange({ state: 'dismissed' })}>Dismiss</button>
        </div>
      )}
      {action.state === 'done' && <span className="action-done"><Icon name="check" size={18} /> Done</span>}
      {action.state === 'dismissed' && <span className="action-muted">Dismissed</span>}
      {action.state === 'error' && (
        <button type="button" className="btn ghost" onClick={confirm} disabled={busy}>Retry</button>
      )}
    </div>
  );
}
