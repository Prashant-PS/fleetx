import { useEffect, useRef, useState } from 'react';
import { api, formatDateTime } from './api.js';
import { useChat } from './chat/ChatContext.jsx';
import { Link, navigate } from './router.jsx';
import Icon from './ui/Icon.jsx';

const NAV = [
  { to: '/fleet', icon: 'fleet', label: 'My fleet' },
  { to: '/agents', icon: 'agents', label: 'AI agents' },
  { to: '/custom-agents', icon: 'plus', label: 'Custom agents' },
  { to: '/saved', icon: 'bookmark', label: 'Saved' },
  { to: '/schedules', icon: 'calendar', label: 'Schedules' },
  { to: '/apps', icon: 'apps', label: 'Fleet apps' },
];

/** Sidebar + top bar around every AI+ page. */
export default function Shell({ path, user, onRename, children }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const { reset, turns } = useChat();

  useEffect(() => setMenuOpen(false), [path]);

  const isActive = (to) => path === to || path.startsWith(to + '/');

  return (
    <div className="shell">
      <aside className={menuOpen ? 'sidebar open' : 'sidebar'}>
        <Link to="/" className="brand">
          <span className="brand-logo">
            <img src="/fleet-bharat-logo.png" alt="Fleet Bharat" width="168" height="46" />
            <em>AI+</em>
          </span>
          <span className="brand-tag">Smarter fleets, fewer surprises.</span>
        </Link>
        <nav>
          <button type="button" className={path === '/' || path === '/chat' ? 'nav-item new-chat active' : 'nav-item new-chat'}
            onClick={() => {
              reset();
              navigate('/');
            }}>
            <Icon name="chat" /> New chat
          </button>
          {turns.length > 0 && path !== '/chat' && (
            <Link to="/chat" className="nav-item sub"><Icon name="arrow" size={16} /> Back to current chat</Link>
          )}
          {NAV.map((n) => (
            <Link key={n.to} to={n.to} className={isActive(n.to) ? 'nav-item active' : 'nav-item'}>
              <Icon name={n.icon} /> {n.label}
            </Link>
          ))}
          <Link to="/search" className="nav-item legacy">
            <Icon name="legacy" />
            <span>Classic search<small>Access the earlier search page</small></span>
          </Link>
        </nav>
        <Link to="/help" className={isActive('/help') ? 'nav-item help active' : 'nav-item help'}><Icon name="help" /> Help &amp; support</Link>
      </aside>
      {menuOpen && <div className="sidebar-backdrop" onClick={() => setMenuOpen(false)} />}

      <div className="main">
        <header className="topbar">
          <button type="button" className="icon-btn menu-btn" onClick={() => setMenuOpen(true)} aria-label="Open menu"><Icon name="menu" /></button>
          <div className="topbar-spacer" />
          <Notifications />
          <Profile user={user} onRename={onRename} />
        </header>
        <main className="content">{children}</main>
      </div>
    </div>
  );
}

function Notifications() {
  const [items, setItems] = useState([]);
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  const load = () => api.get('/api/notifications').then(setItems).catch(() => {});
  useEffect(() => {
    load();
    const t = setInterval(load, 30000);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    const close = (e) => ref.current && !ref.current.contains(e.target) && setOpen(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  const unread = items.filter((n) => !n.read).length;
  const toggle = async () => {
    setOpen(!open);
    if (!open && unread) {
      await api.post('/api/notifications/read');
      setTimeout(load, 1500);
    }
  };

  return (
    <div className="popover-wrap" ref={ref}>
      <button type="button" className="icon-btn bell" onClick={toggle} aria-label={`Notifications${unread ? `, ${unread} unread` : ''}`}>
        <Icon name="bell" />
        {unread > 0 && <span className="badge-dot">{unread > 9 ? '9+' : unread}</span>}
      </button>
      {open && (
        <div className="popover notifications">
          <div className="popover-head">Notifications</div>
          {items.length === 0 && <p className="empty small">Nothing yet. Scheduled reports and reminders show up here.</p>}
          {items.slice(0, 12).map((n) => (
            <Link key={n.id} to={n.link || '/schedules'} className={n.read ? 'notif' : 'notif unread'} onClick={() => setOpen(false)}>
              <strong>{n.title}</strong>
              <span>{n.body}</span>
              <small>{formatDateTime(n.createdAt)}</small>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

function Profile({ user, onRename }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(user.name);
  const ref = useRef(null);
  useEffect(() => setName(user.name), [user.name]);
  useEffect(() => {
    const close = (e) => ref.current && !ref.current.contains(e.target) && setOpen(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);
  const initials = (user.name || 'Fleet Manager').split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase();
  return (
    <div className="popover-wrap" ref={ref}>
      <button type="button" className="profile" onClick={() => setOpen(!open)} aria-label="Profile">
        <span className="avatar">{initials}</span>
        <span className="profile-text"><strong>{user.name || 'Set your name'}</strong><small>{user.role}</small></span>
      </button>
      {open && (
        <form className="popover profile-pop" onSubmit={(e) => {
          e.preventDefault();
          onRename(name.trim());
          setOpen(false);
        }}>
          <label>Your name<input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Priya Sharma" autoFocus /></label>
          <p className="fine-print">Used in the greeting and by AI+. Stored in this browser only.</p>
          <button type="submit" className="btn primary">Save</button>
        </form>
      )}
    </div>
  );
}
