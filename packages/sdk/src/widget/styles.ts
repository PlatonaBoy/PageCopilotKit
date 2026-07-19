export const WIDGET_CSS = `
:host {
  all: initial;
  font-family: "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
}
* { box-sizing: border-box; }
.launcher {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2147483000;
  width: 56px;
  height: 56px;
  border: none;
  border-radius: 50%;
  background: #0f4c81;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(15, 76, 129, 0.35);
}
.launcher:hover { background: #0b3a63; }
.panel {
  position: fixed;
  right: 24px;
  bottom: 96px;
  z-index: 2147483000;
  width: min(380px, calc(100vw - 32px));
  height: min(560px, calc(100vh - 120px));
  display: flex;
  flex-direction: column;
  background: #f7f9fc;
  color: #152033;
  border: 1px solid #d7e0ea;
  border-radius: 16px;
  box-shadow: 0 16px 40px rgba(21, 32, 51, 0.18);
  overflow: hidden;
}
.panel[hidden] { display: none !important; }
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  background: linear-gradient(135deg, #0f4c81, #1f6aa5);
  color: #fff;
}
.header h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.header button {
  border: none;
  background: transparent;
  color: #fff;
  font-size: 18px;
  cursor: pointer;
  line-height: 1;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bubble {
  max-width: 92%;
  padding: 10px 12px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.user {
  align-self: flex-end;
  background: #0f4c81;
  color: #fff;
}
.bubble.assistant {
  align-self: flex-start;
  background: #fff;
  border: 1px solid #e2e8f0;
}
.bubble.assistant :is(p, ul, ol) { margin: 0 0 0.5em; }
.bubble.assistant :is(p, ul, ol):last-child { margin-bottom: 0; }
.composer {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid #d7e0ea;
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
.composer button {
  border: none;
  border-radius: 10px;
  padding: 0 14px;
  background: #0f4c81;
  color: #fff;
  font-weight: 600;
  cursor: pointer;
}
.composer button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.empty {
  color: #5b6b7c;
  font-size: 13px;
  text-align: center;
  margin: auto 0;
  padding: 24px;
}
`;
