import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { api } from './api.js';
import { ChatProvider } from './chat/ChatContext.jsx';
import ClassicSearch from './ClassicSearch.jsx';
import { AgentDetailPage, AgentsPage } from './pages/AgentPages.jsx';
import ChatPage from './pages/ChatPage.jsx';
import { CustomAgentFormPage, CustomAgentRunPage, CustomAgentsPage } from './pages/CustomAgentPages.jsx';
import { FleetAppsPage, HelpPage, MyFleetPage } from './pages/FleetPages.jsx';
import HomePage from './pages/HomePage.jsx';
import { TyresPage } from './pages/TyrePages.jsx';
import { SavedItemPage, SavedPage, SchedulesPage } from './pages/WorkspacePages.jsx';
import { Link, useRoute } from './router.jsx';
import Shell from './Shell.jsx';
import './App.css';
import './shell.css';

const NAME_KEY = 'fleetboard.userName';

function readName() {
  try {
    return localStorage.getItem(NAME_KEY);
  } catch {
    return null;
  }
}

function Root() {
  const { path, query } = useRoute();
  const [me, setMe] = useState({ name: readName() || '', role: 'Fleet Manager' });
  const [aiEnabled, setAiEnabled] = useState(false);

  useEffect(() => {
    api.get('/api/me').then((m) => setMe((cur) => ({ name: cur.name || m.name || '', role: m.role }))).catch(() => {});
    api.get('/api/info').then((i) => setAiEnabled(i.aiEnabled)).catch(() => {});
  }, []);

  const rename = (name) => {
    setMe({ ...me, name });
    try {
      localStorage.setItem(NAME_KEY, name);
    } catch {
      // name just won't persist
    }
  };

  if (path === '/search') {
    return (
      <>
        <Link to="/" className="back-to-ai">✦ Fleet Bharat AI+</Link>
        <ClassicSearch key={query.toString()} />
      </>
    );
  }

  return (
    <ChatProvider userName={me.name}>
      <Shell path={path} user={me} onRename={rename}>
        <Page path={path} query={query} me={me} aiEnabled={aiEnabled} />
      </Shell>
    </ChatProvider>
  );
}

function Page({ path, query, me, aiEnabled }) {
  const parts = path.split('/').filter(Boolean);
  switch (parts[0]) {
    case undefined:
      return <HomePage userName={me.name} aiEnabled={aiEnabled} />;
    case 'chat':
      return <ChatPage />;
    case 'fleet':
      return <MyFleetPage query={query} />;
    case 'tyres':
      return <TyresPage query={query} />;
    case 'agents':
      return parts[1] ? <AgentDetailPage id={parts[1]} /> : <AgentsPage />;
    case 'custom-agents':
      if (parts[1] === 'new') return <CustomAgentFormPage />;
      if (parts[1] && parts[2] === 'edit') return <CustomAgentFormPage id={parts[1]} />;
      return parts[1] ? <CustomAgentRunPage id={parts[1]} /> : <CustomAgentsPage />;
    case 'saved':
      return parts[1] ? <SavedItemPage id={parts[1]} /> : <SavedPage />;
    case 'schedules':
      return <SchedulesPage />;
    case 'apps':
      return <FleetAppsPage />;
    case 'help':
      return <HelpPage aiEnabled={aiEnabled} />;
    default:
      return (
        <div className="page">
          <h1>Page not found</h1>
          <Link to="/">Go to Fleet Bharat AI+</Link>
        </div>
      );
  }
}

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>
);
