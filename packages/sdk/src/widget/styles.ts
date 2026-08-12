export const WIDGET_CSS = `
:host {
  all: initial;
  font-family: var(--copilot-font, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif);
  --copilot-primary: #0f4c81;
  --copilot-primary-dark: #0b3a63;
  --copilot-surface: #f7f9fc;
  --copilot-card: #ffffff;
  --copilot-ink: #152033;
  --copilot-muted: #5b6b7c;
  --copilot-line: #d7e0ea;
  --copilot-z: 2147483000;
  --copilot-offset: 24px;
}
* { box-sizing: border-box; }
button { font-family: inherit; }

.launcher {
  position: fixed;
  bottom: calc(var(--copilot-offset) + env(safe-area-inset-bottom, 0px));
  z-index: var(--copilot-z);
  width: 56px;
  height: 56px;
  border: none;
  border-radius: 50%;
  background: var(--copilot-primary);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(15, 76, 129, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}
.launcher img { width: 28px; height: 28px; border-radius: 50%; object-fit: cover; }
.launcher:hover { background: var(--copilot-primary-dark); }
.launcher:focus-visible { outline: 3px solid #ffffff; outline-offset: 2px; }

.panel {
  position: fixed;
  bottom: calc(var(--copilot-offset) + 72px + env(safe-area-inset-bottom, 0px));
  z-index: var(--copilot-z);
  width: min(390px, calc(100vw - 32px));
  height: min(580px, calc(100vh - 140px));
  display: flex;
  flex-direction: column;
  background: var(--copilot-surface);
  color: var(--copilot-ink);
  border: 1px solid var(--copilot-line);
  border-radius: 16px;
  box-shadow: 0 16px 40px rgba(21, 32, 51, 0.18);
  overflow: hidden;
}
.panel[hidden] { display: none !important; }

:host([data-position="bottom-right"]) .launcher,
:host(:not([data-position])) .launcher { right: var(--copilot-offset); }
:host([data-position="bottom-right"]) .panel,
:host(:not([data-position])) .panel { right: var(--copilot-offset); }
:host([data-position="bottom-left"]) .launcher { left: var(--copilot-offset); }
:host([data-position="bottom-left"]) .panel { left: var(--copilot-offset); }

.header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  background: var(--copilot-primary);
  color: #fff;
}
.header img { width: 22px; height: 22px; border-radius: 4px; }
.header h1 {
  margin: 0;
  flex: 1;
  font-size: 15px;
  font-weight: 600;
}
.icon-button {
  border: none;
  background: transparent;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  line-height: 1;
  padding: 4px 6px;
  border-radius: 6px;
}
.icon-button:hover { background: rgba(255, 255, 255, 0.18); }
.icon-button:focus-visible { outline: 2px solid #fff; outline-offset: 1px; }
.icon-button.close { font-size: 18px; }

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  scroll-behavior: smooth;
}
.row { display: flex; flex-direction: column; gap: 4px; max-width: 92%; }
.row.user { align-self: flex-end; align-items: flex-end; }
.row.assistant { align-self: flex-start; align-items: flex-start; }

.bubble {
  padding: 10px 12px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.row.user .bubble { background: var(--copilot-primary); color: #fff; }
.row.assistant .bubble { background: var(--copilot-card); border: 1px solid #e2e8f0; }
.row.assistant .bubble.error { border-color: #f0c2c2; background: #fff6f6; }
.bubble :is(p, ul, ol) { margin: 0 0 0.5em; }
.bubble :is(p, ul, ol):last-child { margin-bottom: 0; }
.bubble pre {
  background: #f1f5f9;
  padding: 8px;
  border-radius: 8px;
  overflow-x: auto;
  font-size: 12px;
}
.bubble code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

.row-actions { display: flex; gap: 8px; }
.row-actions button {
  border: 1px solid var(--copilot-line);
  background: #fff;
  color: var(--copilot-muted);
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  cursor: pointer;
}
.row-actions button:hover { color: var(--copilot-ink); border-color: #b9c7d6; }

.typing { display: inline-flex; align-items: center; gap: 4px; height: 18px; }
.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--copilot-muted);
  animation: copilot-blink 1.2s infinite ease-in-out;
}
.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes copilot-blink {
  0%, 80%, 100% { opacity: 0.25; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}
@media (prefers-reduced-motion: reduce) {
  .typing span { animation: none; opacity: 0.6; }
  .messages { scroll-behavior: auto; }
}

.composer {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid var(--copilot-line);
  background: #fff;
}
.composer textarea {
  flex: 1;
  resize: none;
  min-height: 44px;
  max-height: 120px;
  border: 1px solid #c9d5e3;
  border-radius: 10px;
  padding: 10px 12px;
  font: inherit;
  color: inherit;
  background: #fff;
}
.composer textarea:focus-visible { outline: 2px solid var(--copilot-primary); outline-offset: 1px; }
.composer button {
  border: none;
  border-radius: 10px;
  padding: 0 14px;
  background: var(--copilot-primary);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
  min-width: 64px;
}
.composer button.stop { background: #a13d3d; }
.composer button:disabled { opacity: 0.5; cursor: not-allowed; }

.empty {
  color: var(--copilot-muted);
  font-size: 13px;
  text-align: center;
  margin: auto 0;
  padding: 24px;
  line-height: 1.6;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 520px) {
  .panel {
    right: 0 !important;
    left: 0 !important;
    bottom: 0 !important;
    width: 100vw;
    height: 100vh;
    height: 100dvh;
    border-radius: 0;
    border: none;
  }
}
`;
