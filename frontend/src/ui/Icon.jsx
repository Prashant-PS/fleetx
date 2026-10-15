/** Line icons (24px grid, stroke = currentColor). */
const PATHS = {
  chat: 'M4 5h16v10H8l-4 4V5z',
  fleet: 'M3 7h11v8H3zM14 10h4l3 3v2h-7M6.5 18a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM17.5 18a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z',
  agents: 'M8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM2 20c0-3 3-5 6-5s6 2 6 5M14 15.5c.6-.3 1.3-.5 2-.5 3 0 6 2 6 5',
  plus: 'M12 5v14M5 12h14',
  bookmark: 'M6 3h12v18l-6-4-6 4z',
  calendar: 'M4 6h16v14H4zM4 10h16M8 3v4M16 3v4',
  apps: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
  legacy: 'M4 4h16v16H4zM9 9l6 6M15 9v6H9',
  help: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17.5v.5',
  bell: 'M6 16V11a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4',
  analysis: 'M5 20V10M10 20V4M15 20v-7M20 20v-12',
  report: 'M6 3h9l4 4v14H6zM9 12h6M9 16h6M9 8h3',
  action: 'M13 2L4 14h7l-1 8 9-12h-7z',
  mic: 'M12 15a3 3 0 0 0 3-3V6a3 3 0 1 0-6 0v6a3 3 0 0 0 3 3zM6 11a6 6 0 0 0 12 0M12 17v4',
  send: 'M4 12l16-8-6 16-2-7z',
  stop: 'M7 7h10v10H7z',
  arrow: 'M5 12h14M13 6l6 6-6 6',
  hygiene: 'M3 7h14v10H3zM17 10l4 2-4 2M7 11h6',
  compliance: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM9 12l2 2 4-4',
  operations: 'M14.5 3.5a5 5 0 0 0-6 6L3 15l3 3 5.5-5.5a5 5 0 0 0 6-6l-3 3-3-3z',
  ev: 'M13 2L5 13h6l-1 9 8-11h-6z',
  reporting: 'M6 3h12v18H6zM9 8h6M9 12h6M9 16h3',
  custom: 'M12 3l2.5 5 5.5.8-4 3.9.9 5.5L12 15.6 7.1 18.2 8 12.7 4 8.8 9.5 8z',
  copy: 'M8 8h12v12H8zM4 16V4h12',
  trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3',
  play: 'M7 5l12 7-12 7z',
  check: 'M5 12l5 5 9-10',
  close: 'M6 6l12 12M18 6L6 18',
  menu: 'M4 7h16M4 12h16M4 17h16',
  news: 'M4 5h13v14H6a2 2 0 0 1-2-2zM17 9h3v8a2 2 0 0 1-2 2M8 9h5M8 13h5',
  matrix: 'M4 4h16v16H4zM4 10h16M4 16h16M10 4v16',
  sparkle: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z',
  external: 'M14 4h6v6M20 4l-9 9M18 14v6H4V6h6',
  uptime: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2M3 12h2M19 12h2',
  tyre: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM12 3v6M12 15v6M3 12h6M15 12h6',
  leaf: 'M5 19c0-8 5-14 15-15-1 10-7 15-15 15zM5 19l7-7',
  wallet: 'M4 7h15a1 1 0 0 1 1 1v11H4a1 1 0 0 1-1-1V6a2 2 0 0 1 2-2h11v3M16 13h2',
};

export default function Icon({ name, size = 20, className = '' }) {
  return (
    <svg
      className={`icon ${className}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={PATHS[name] || PATHS.sparkle} />
    </svg>
  );
}
