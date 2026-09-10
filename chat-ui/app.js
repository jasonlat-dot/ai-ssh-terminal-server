/**
 * AI Chat Application
 * Entry point: index.html
 */

// =============================================
// DOM Elements
// =============================================
const $ = id => document.getElementById(id);

// Header
const userIdInput       = $('userIdInput');
const randomUserIdBtn   = $('randomUserIdBtn');

// Toolbar
const agentSelect       = $('agentSelect');
const agentDesc         = $('agentDesc');
const refreshAgentBtn   = $('refreshAgentBtn');
const newSessionBtn     = $('newSessionBtn');
const clearChatBtn      = $('clearChatBtn');

// Sidebar
const sidebar            = $('sidebar');
const sessionListEl      = $('sessionList');

// Chat
const chatMessages       = $('chatMessages');
const welcomeScreen      = $('welcomeScreen');
const welcomeHints       = $('welcomeHints');
const inputArea          = $('inputArea');
const messageInput       = $('messageInput');
const sendBtn             = $('sendBtn');
const streamToggle       = $('streamToggle');

// Modal & Toast
const expiredModal       = $('expiredModal');
const modalNewSessionBtn = $('modalNewSessionBtn');
const modalContinueBtn   = $('modalContinueBtn');
const loadingToast       = $('loadingToast');

// =============================================
// State
// =============================================
let currentSession = null;   // { sessionId, userId, agentId, agentName }
let isLoading = false;
let agentList = [];          // [{agentId, agentName, agentDesc}]
let isStreaming = false;     // 流式输出状态
let currentAiMessageEl = null; // 当前 AI 消息元素（用于流式更新）

// =============================================
// LocalStorage Keys
// =============================================
const LS_SESSIONS = 'chat_sessions';  // array of session objects
const LS_USER_ID  = 'chat_user_id';

// =============================================
// Markdown：marked + highlight.js
// =============================================
marked.setOptions({
    highlight: function(code, lang) {
        if (lang && hljs.getLanguage(lang)) {
            try {
                return hljs.highlight(code, { language: lang }).value;
            } catch (err) {}
        }
        return hljs.highlightAuto(code).value;
    },
    breaks: true,
    gfm: true
});

// 渲染 Markdown 内容
function renderMarkdown(content) {
    if (!content) return '';
    return marked.parse(content);
}

/**
 * 流式输出时的 Markdown 渲染。
 * 未闭合的 ``` 围栏会让 marked 生成不完整的 HTML（如未闭合的 pre/code），
 * 浏览器纠错后会出现「一半代码块、一半原文」或围栏字符外露。
 * 在围栏数量为奇数时临时补上闭合 ``` 仅用于预览，不改变实际 buffer。
 */
function renderMarkdownStreaming(content) {
    if (!content) return '';
    const fenceCount = (content.match(/```/g) || []).length;
    const parseSource = fenceCount % 2 === 1 ? content + '\n```\n' : content;
    return marked.parse(parseSource);
}

// HTML 转义（防 XSS）
function escHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

// =============================================
// API Helpers
// =============================================
async function apiGet(path) {
    const res = await fetch(`${API_BASE}${path}`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
    });
    return res.json();
}

async function apiPost(path, body) {
    const res = await fetch(`${API_BASE}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    return res.json();
}

function isSuccess(res) {
    return res && res.code === 'SUCCESS_0000';
}

// =============================================
// Toast & Loading
// =============================================
function showLoading(text = 'AI 思考中...') {
    loadingToast.querySelector('span').textContent = text;
    loadingToast.classList.add('show');
    isLoading = true;
    sendBtn.disabled = true;
}

function hideLoading() {
    loadingToast.classList.remove('show');
    isLoading = false;
    updateSendBtnState();
}

// =============================================
// Time Formatting
// =============================================
function formatTime(date) {
    const now = new Date();
    const d = new Date(date);
    const isToday = d.toDateString() === now.toDateString();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const min = String(d.getMinutes()).padStart(2, '0');
    if (isToday) {
        return `今天 ${hh}:${min}`;
    }
    return `${yyyy}-${mm}-${dd} ${hh}:${min}`;
}

function nowStr() {
    return new Date().toISOString();
}

// =============================================
// UserID Management
// =============================================
function generateUserId() {
    return 'user_' + Math.random().toString(36).substring(2, 10) + Date.now().toString(36);
}

function saveUserId(uid) {
    localStorage.setItem(LS_USER_ID, uid);
}

function loadUserId() {
    return localStorage.getItem(LS_USER_ID) || '';
}

function initUserId() {
    const saved = loadUserId();
    if (saved) {
        userIdInput.value = saved;
    }
}

randomUserIdBtn.addEventListener('click', () => {
    userIdInput.value = generateUserId();
    saveUserId(userIdInput.value);
    updateSendBtnState();
});

userIdInput.addEventListener('input', () => {
    if (userIdInput.value.trim()) {
        saveUserId(userIdInput.value.trim());
    }
    updateSendBtnState();
});

// =============================================
// Agent Management
// =============================================
async function loadAgentList(showMsg = true) {
    try {
        refreshAgentBtn.disabled = true;
        refreshAgentBtn.classList.add('spinning');
        const res = await apiGet('/agent/query_ai_agent_config_list');
        if (isSuccess(res) && res.data) {
            agentList = res.data;
            renderAgentOptions();
            renderWelcomeCards();
            if (showMsg) showToast(`已加载 ${agentList.length} 个智能体`);
        } else {
            showToast('加载智能体列表失败: ' + (res.info || '未知错误'));
        }
    } catch (e) {
        console.error('Failed to load agent list:', e);
        showToast('加载智能体列表失败，请检查网络');
    } finally {
        refreshAgentBtn.disabled = false;
        refreshAgentBtn.classList.remove('spinning');
    }
}

refreshAgentBtn.addEventListener('click', () => {
    loadAgentList();
});

function renderAgentOptions() {
    agentSelect.innerHTML = '<option value="">-- 请选择智能体 --</option>';
    agentList.forEach(agent => {
        const opt = document.createElement('option');
        opt.value = agent.agentId;
        opt.textContent = agent.agentName;
        opt.dataset.desc = agent.agentDesc || '';
        agentSelect.appendChild(opt);
    });
}

function renderWelcomeCards() {
    welcomeHints.innerHTML = '';
    agentList.forEach(agent => {
        const card = document.createElement('div');
        card.className = 'agent-card';
        card.innerHTML = `
            <div class="agent-card-name">${escHtml(agent.agentName)}</div>
            <div class="agent-card-desc">${escHtml(agent.agentDesc || '暂无描述')}</div>
        `;
        card.addEventListener('click', () => {
            if (!userIdInput.value.trim()) {
                userIdInput.focus();
                userIdInput.style.borderColor = '#e53e3e';
                setTimeout(() => { userIdInput.style.borderColor = ''; }, 1500);
                return;
            }
            agentSelect.value = agent.agentId;
            agentDesc.textContent = agent.agentDesc || '';
            updateSendBtnState();
            messageInput.focus();
            showToast('已选择智能体 "' + agent.agentName + '"，在下方输入消息开始对话');
        });
        welcomeHints.appendChild(card);
    });
}

agentSelect.addEventListener('change', () => {
    const selected = agentSelect.options[agentSelect.selectedIndex];
    agentDesc.textContent = selected.dataset.desc || '';
    updateSendBtnState();
});

// =============================================
// Send Button State
// =============================================
function updateSendBtnState() {
    const hasUserId = !!userIdInput.value.trim();
    const hasAgent  = !!agentSelect.value;
    const hasMsg    = !!messageInput.value.trim();
    const hasSession = !!currentSession;

    sendBtn.disabled = !(hasUserId && hasAgent && hasMsg && !isLoading);
    messageInput.disabled = !(hasUserId && hasAgent);
    newSessionBtn.disabled = !(hasUserId && hasAgent);
    clearChatBtn.disabled = !currentSession;
}

messageInput.addEventListener('input', () => {
    autoResize(messageInput);
    updateSendBtnState();
});

// =============================================
// Auto-resize textarea
// =============================================
function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 120) + 'px';
}

// =============================================
// Message Input Key Events
// =============================================
messageInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        if (!sendBtn.disabled) {
            handleSend();
        }
    }
});

// =============================================
// Session Management
// =============================================

// Load sessions from LocalStorage
function getStoredSessions() {
    try {
        return JSON.parse(localStorage.getItem(LS_SESSIONS) || '[]');
    } catch { return []; }
}

function saveSessions(sessions) {
    localStorage.setItem(LS_SESSIONS, JSON.stringify(sessions));
}

// Render session list in sidebar
function renderSessionList() {
    const sessions = getStoredSessions();
    if (sessions.length === 0) {
        sessionListEl.innerHTML = '<div class="sidebar-empty">暂无会话记录<br>新建一个开始对话吧</div>';
        return;
    }

    sessionListEl.innerHTML = '';
    // newest first
    [...sessions].reverse().forEach(session => {
        const item = document.createElement('div');
        item.className = 'session-item' + (currentSession && currentSession.sessionId === session.sessionId ? ' active' : '');

        const timeLabel = session.lastTime ? formatTime(session.lastTime) : '';

        item.innerHTML = `
            <div class="session-item-name">${escHtml(session.agentName || '会话')}</div>
            <div class="session-item-meta">${escHtml(session.lastMessage || '新会话')}</div>
            <button class="session-item-delete" title="删除此会话">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
            </button>
        `;

        item.querySelector('.session-item-delete').addEventListener('click', (e) => {
            e.stopPropagation();
            deleteSession(session.sessionId);
        });

        item.addEventListener('click', () => {
            resumeSession(session);
        });

        sessionListEl.appendChild(item);
    });
}

// Resume a session (validate + restore)
async function resumeSession(session) {
    if (currentSession && currentSession.sessionId === session.sessionId) return;

    // Validate session
    try {
        const res = await apiPost('/agent/validateSessionId', {
            agentId: session.agentId,
            userId: session.userId,
            sessionId: session.sessionId
        });

        if (isSuccess(res) && res.data === true) {
            currentSession = session;
            userIdInput.value = session.userId;
            saveUserId(session.userId);
            agentSelect.value = session.agentId;
            agentDesc.textContent = session.agentName || '';
            welcomeScreen.style.display = 'none';
            renderSessionList();
            updateSendBtnState();
            renderSessionMessages(session.messages || []);
            showToast('已恢复会话');
        } else {
            showExpiredModal(session);
        }
    } catch (e) {
        console.error('Session validation failed:', e);
        showToast('会话验证失败');
    }
}

// Render messages from session history
function renderSessionMessages(messages) {
    chatMessages.innerHTML = '';
    chatMessages.appendChild(welcomeScreen);
    welcomeScreen.style.display = 'none';

    messages.forEach(msg => {
        const msgEl = document.createElement('div');
        msgEl.className = `message ${msg.role}`;
        const avatarText = msg.role === 'user' ? 'U' : 'AI';
        msgEl.innerHTML = `
            <div class="message-avatar">${avatarText}</div>
            <div class="message-body">
                <div class="message-content"></div>
                <div class="message-time">${formatTime(new Date(msg.timestamp))}</div>
            </div>
        `;
        const mc = msgEl.querySelector('.message-content');
        mc.innerHTML = renderMarkdown(msg.content || '');
        chatMessages.appendChild(msgEl);
    });
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

// Show expired modal
function showExpiredModal(session) {
    expiredModal.classList.add('show');

    modalNewSessionBtn.onclick = () => {
        expiredModal.classList.remove('show');
        deleteSession(session.sessionId);
        agentSelect.value = '';
        agentDesc.textContent = '';
        currentSession = null;
        welcomeScreen.style.display = '';
        clearChatMessages();
        renderSessionList();
        updateSendBtnState();
    };

    modalContinueBtn.onclick = () => {
        expiredModal.classList.remove('show');
        currentSession = session;
        userIdInput.value = session.userId;
        saveUserId(session.userId);
        agentSelect.value = session.agentId;
        agentDesc.textContent = session.agentName || '';
        welcomeScreen.style.display = 'none';
        renderSessionList();
        updateSendBtnState();
        renderSessionMessages(session.messages || []);
        showToast('会话已继续（上下文不再关联）');
    };
}

modalContinueBtn.addEventListener('click', () => {});

// Delete session
function deleteSession(sessionId) {
    const sessions = getStoredSessions().filter(s => s.sessionId !== sessionId);
    saveSessions(sessions);
    if (currentSession && currentSession.sessionId === sessionId) {
        currentSession = null;
        clearChatMessages();
        welcomeScreen.style.display = '';
        renderSessionList();
        updateSendBtnState();
    } else {
        renderSessionList();
    }
    showToast('会话已删除');
}

// Start a new session
async function startSession() {
    if (!userIdInput.value.trim() || !agentSelect.value) return;

    try {
        const res = await apiPost('/agent/create_session', {
            agentId: agentSelect.value,
            userId: userIdInput.value.trim()
        });

        if (isSuccess(res) && res.data && res.data.sessionId) {
            const selected = agentSelect.options[agentSelect.selectedIndex];
            const session = {
                sessionId: res.data.sessionId,
                userId: userIdInput.value.trim(),
                agentId: agentSelect.value,
                agentName: selected.textContent,
                lastMessage: '新会话',
                lastTime: nowStr(),
                messages: []
            };

            // If switching agent mid-session, treat as new session
            currentSession = session;
            addSession(session);
            welcomeScreen.style.display = 'none';
            clearChatMessages();
            renderSessionList();
            updateSendBtnState();
            messageInput.focus();
        } else {
            showToast('创建会话失败: ' + (res.info || '未知错误'));
        }
    } catch (e) {
        console.error('Create session error:', e);
        showToast('创建会话失败，请检查网络');
    }
}

// Add or update session in storage
function addSession(session) {
    const sessions = getStoredSessions().filter(s => s.sessionId !== session.sessionId);
    sessions.push(session);
    saveSessions(sessions);
}

function updateCurrentSession(lastMessage) {
    if (!currentSession) return;
    currentSession.lastMessage = lastMessage;
    currentSession.lastTime = nowStr();
    addSession(currentSession);
    renderSessionList();
}

// =============================================
// Chat Core
// =============================================
async function handleSend() {
    if (isLoading) return;
    const msg = messageInput.value.trim();
    if (!msg) return;

    // If no session, create one first
    if (!currentSession) {
        await startSession();
        if (!currentSession) return;
    }

    const userId = userIdInput.value.trim();
    const { sessionId, agentId } = currentSession;

    // Append user message
    appendMessage('user', msg);
    messageInput.value = '';
    autoResize(messageInput);

    // Save to session history
    if (!currentSession.messages) currentSession.messages = [];
    currentSession.messages.push({ role: 'user', content: msg, timestamp: Date.now() });
    updateCurrentSession(msg.length > 20 ? msg.substring(0, 20) + '...' : msg);

    showLoading(streamToggle.checked ? 'AI 思考中（流式）...' : 'AI 思考中...');

    try {
        if (streamToggle.checked) {
            await handleStreamChat(userId, sessionId, agentId, msg);
        } else {
            await handleNormalChat(userId, sessionId, agentId, msg);
        }
    } catch (e) {
        hideLoading();
        console.error('Chat error:', e);
        appendMessage('ai', '网络错误，请稍后重试');
    }

    updateSendBtnState();
}

sendBtn.addEventListener('click', handleSend);

// =============================================
// Normal Chat (非流式)
// =============================================
async function handleNormalChat(userId, sessionId, agentId, msg) {
    const res = await apiPost('/agent/chat', {
        agentId,
        userId,
        sessionId,
        message: msg
    });

    hideLoading();

    if (isSuccess(res) && res.data && res.data.content) {
        appendMessage('ai', res.data.content);
        if (!currentSession.messages) currentSession.messages = [];
        currentSession.messages.push({ role: 'ai', content: res.data.content, timestamp: Date.now() });
        const preview = res.data.content.replace(/[#*`>\-\[\]]/g, '').trim();
        updateCurrentSession(preview.substring(0, 30));
    } else {
        appendMessage('ai', `请求失败: ${res.info || '未知错误'}`);
    }
}

// =============================================
// Stream Chat (流式 SSE)
// =============================================
async function handleStreamChat(userId, sessionId, agentId, msg) {
    isStreaming = true;

    const msgEl = document.createElement('div');
    msgEl.className = 'message ai';
    msgEl.innerHTML = `
        <div class="message-avatar">AI</div>
        <div class="message-body">
            <div class="message-content streaming-content"></div>
            <div class="message-time">${formatTime(new Date())}</div>
        </div>
    `;
    chatMessages.appendChild(msgEl);
    currentAiMessageEl = msgEl;
    const contentEl = msgEl.querySelector('.message-content');

    if (welcomeScreen.style.display !== 'none') {
        welcomeScreen.style.display = 'none';
    }

    let buffer = '';
    let pollTimer = null;
    const POLL_MS = 100;

    const flush = () => {
        pollTimer = null;
        if (!buffer) return;
        contentEl.innerHTML = renderMarkdownStreaming(buffer);
        chatMessages.scrollTop = chatMessages.scrollHeight;
    };

    const scheduleFlush = () => {
        if (pollTimer) return;
        pollTimer = setTimeout(flush, POLL_MS);
    };

    try {
        const resp = await fetch(`${API_BASE}/agent/chat_stream`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ agentId, userId, sessionId, message: msg })
        });

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();

        function readStream() {
            reader.read().then(({ done, value }) => {
                if (done) {
                    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
                    // 最终完整渲染
                    contentEl.innerHTML = renderMarkdown(buffer);
                    // appendMessage("ai", buffer)
                    // 移除流式样式类，停止光标闪烁
                    contentEl.classList.remove('streaming-content');
                    // 保存 AI 回复到会话历史
                    if (currentSession) {
                        if (!currentSession.messages) currentSession.messages = [];
                        currentSession.messages.push({ role: 'ai', content: buffer, timestamp: Date.now() });
                        const preview = buffer.replace(/[#*`>\-\[\]]/g, '').trim();
                        updateCurrentSession(preview.substring(0, 30));
                    }
                    isStreaming = false;
                    currentAiMessageEl = null;
                    hideLoading();
                    updateSendBtnState();
                    return;
                }

                const chunk = decoder.decode(value, { stream: true });
                console.log('[Stream]', chunk)
                const lines = chunk.split('\n');

                for (const line of lines) {
                    const trimmedLine = line;
                    // 跳过结束标识
                    if (trimmedLine === 'data:' || trimmedLine === 'data: [DONE]' || trimmedLine === 'data:[DONE]') {
                        continue;
                    }
                    if (trimmedLine.startsWith('data: ')) {
                        const raw = trimmedLine.substring(6); // 内容: " Java" / " " / "" / "java"
                        if (raw && raw !== '[DONE]') {
                            console.log("parse: ", raw)
                            buffer += JSON.parse(raw);
                        }
                        scheduleFlush();
                    } else {

                        buffer += line;
                    }
                }

                readStream();
            }).catch(error => {
                console.error('Stream error:', error);
                if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
                // 如果有部分内容，先渲染出来
                if (buffer) {
                    contentEl.innerHTML = renderMarkdown(buffer);
                }
                // 移除流式样式类，停止光标闪烁
                contentEl.classList.remove('streaming-content');
                isStreaming = false;
                currentAiMessageEl = null;
                hideLoading();
                updateSendBtnState();
            });
        }

        readStream();

    } catch (e) {
        console.error('Chat error:', e);
        if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
        hideLoading();
        isStreaming = false;
        currentAiMessageEl = null;
        appendMessage('ai', '网络错误，请稍后重试');
    }
}

// =============================================
// Message Rendering
// =============================================
function appendMessage(role, content) {
    // Hide welcome screen if visible
    if (welcomeScreen.style.display !== 'none') {
        welcomeScreen.style.display = 'none';
    }

    const msgEl = document.createElement('div');
    msgEl.className = `message ${role}`;

    const avatarText = role === 'user' ? 'U' : 'AI';
    const timeText = formatTime(new Date());

    msgEl.innerHTML = `
        <div class="message-avatar">${avatarText}</div>
        <div class="message-body">
            <div class="message-content"></div>
            <div class="message-time">${timeText}</div>
        </div>
    `;

    chatMessages.appendChild(msgEl);
    const mc = msgEl.querySelector('.message-content');
    mc.innerHTML = renderMarkdown(content);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

// 渲染历史消息
function renderHistoryMessages() {
    if (!currentSession || !currentSession.messages) return;
    clearChatMessages();
    for (const msg of currentSession.messages) {
        appendMessage(msg.role, msg.content);
    }
}

function clearChatMessages() {
    chatMessages.innerHTML = '';
    chatMessages.appendChild(welcomeScreen);
}

// =============================================
// Toolbar Actions
// =============================================
newSessionBtn.addEventListener('click', async () => {
    if (!userIdInput.value.trim() || !agentSelect.value) return;
    currentSession = null;
    clearChatMessages();
    welcomeScreen.style.display = '';
    renderSessionList();
    updateSendBtnState();
    showToast('已重置，请发送消息以新建会话');
});

clearChatBtn.addEventListener('click', () => {
    if (!currentSession) return;
    clearChatMessages();
    currentSession.messages = [];
    updateCurrentSession('新会话');
    updateSendBtnState();
});

// =============================================
// Simple Toast (no external lib)
// =============================================
function showToast(msg) {
    const existing = document.querySelector('.app-toast');
    if (existing) existing.remove();

    const toast = document.createElement('div');
    toast.className = 'app-toast';
    toast.textContent = msg;
    document.body.appendChild(toast);

    requestAnimationFrame(() => toast.classList.add('show'));

    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300);
    }, 2000);
}

// Inject toast styles dynamically
(function() {
    const style = document.createElement('style');
    style.textContent = `
        .app-toast {
            position: fixed;
            top: 80px;
            left: 50%;
            transform: translateX(-50%) translateY(-10px);
            background: rgba(30,30,30,0.88);
            color: #fff;
            padding: 8px 20px;
            border-radius: 50px;
            font-size: 13px;
            z-index: 300;
            box-shadow: 0 4px 12px rgba(0,0,0,0.15);
            backdrop-filter: blur(8px);
            opacity: 0;
            transition: all 0.3s ease;
            pointer-events: none;
        }
        .app-toast.show {
            opacity: 1;
            transform: translateX(-50%) translateY(0);
        }
    `;
    document.head.appendChild(style);
})();

// =============================================
// Init
// =============================================
async function init() {
    initUserId();
    renderSessionList();
    updateSendBtnState();
    await loadAgentList();
    updateSendBtnState();
}

init();
