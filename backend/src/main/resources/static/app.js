"use strict";

const POSITIONS = [
    "A1", "D1", "G1", "B2", "D2", "F2", "C3", "D3", "E3",
    "A4", "B4", "C4", "E4", "F4", "G4", "C5", "D5", "E5",
    "B6", "D6", "F6", "A7", "D7", "G7"
];

const COORDINATES = {
    A1: [5, 5], D1: [50, 5], G1: [95, 5],
    B2: [20, 20], D2: [50, 20], F2: [80, 20],
    C3: [35, 35], D3: [50, 35], E3: [65, 35],
    A4: [5, 50], B4: [20, 50], C4: [35, 50], E4: [65, 50], F4: [80, 50], G4: [95, 50],
    C5: [35, 65], D5: [50, 65], E5: [65, 65],
    B6: [20, 80], D6: [50, 80], F6: [80, 80],
    A7: [5, 95], D7: [50, 95], G7: [95, 95]
};

const STORAGE_KEY = "morris-live-session-v1";
const MAX_LOG_ITEMS = 8;

const elements = {
    setupPanel: document.getElementById("setupPanel"),
    gamePanel: document.getElementById("gamePanel"),
    createForm: document.getElementById("createForm"),
    joinForm: document.getElementById("joinForm"),
    joinGameId: document.getElementById("joinGameId"),
    board: document.getElementById("board"),
    connectionPill: document.getElementById("connectionPill"),
    connectionText: document.getElementById("connectionText"),
    playerSide: document.getElementById("playerSide"),
    gameVersion: document.getElementById("gameVersion"),
    gamePhase: document.getElementById("gamePhase"),
    currentPlayer: document.getElementById("currentPlayer"),
    gameIdText: document.getElementById("gameIdText"),
    copyGameId: document.getElementById("copyGameId"),
    leaveGame: document.getElementById("leaveGame"),
    actionPrompt: document.getElementById("actionPrompt"),
    whitePlayerName: document.getElementById("whitePlayerName"),
    blackPlayerName: document.getElementById("blackPlayerName"),
    whitePieces: document.getElementById("whitePieces"),
    blackPieces: document.getElementById("blackPieces"),
    refreshGame: document.getElementById("refreshGame"),
    activityLog: document.getElementById("activityLog"),
    toast: document.getElementById("toast")
};

const client = {
    session: null,
    game: null,
    socket: null,
    stompBuffer: "",
    reconnectTimer: null,
    shouldReconnect: false,
    selectedSource: null,
    pendingAction: false,
    logs: []
};

initializeBoard();
bindEvents();
prefillSharedGame();
restoreSession();

function initializeBoard() {
    for (const position of POSITIONS) {
        const point = document.createElement("button");
        point.type = "button";
        point.className = "position";
        point.dataset.position = position;
        point.dataset.piece = "EMPTY";
        point.setAttribute("aria-label", `${position}, empty`);
        point.style.setProperty("--x", `${COORDINATES[position][0]}%`);
        point.style.setProperty("--y", `${COORDINATES[position][1]}%`);
        point.addEventListener("click", () => handlePositionClick(position));
        elements.board.appendChild(point);
    }
}

function bindEvents() {
    elements.createForm.addEventListener("submit", createGame);
    elements.joinForm.addEventListener("submit", joinGame);
    elements.copyGameId.addEventListener("click", copyGameId);
    elements.leaveGame.addEventListener("click", leaveGame);
    elements.refreshGame.addEventListener("click", () => refreshGame("手动刷新"));
}

function prefillSharedGame() {
    const sharedId = new URLSearchParams(window.location.search).get("game");
    if (sharedId) elements.joinGameId.value = sharedId;
}

async function restoreSession() {
    let saved;
    try {
        saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY));
    } catch {
        sessionStorage.removeItem(STORAGE_KEY);
    }

    if (!saved?.gameId || !saved?.token || !saved?.side) {
        showSetup();
        setConnection("offline", "未加入对局");
        return;
    }

    client.session = saved;
    try {
        const game = await api(`/api/v1/games/${saved.gameId}`);
        enterGame(game, "已恢复当前标签页的玩家会话");
    } catch (error) {
        sessionStorage.removeItem(STORAGE_KEY);
        client.session = null;
        showSetup();
        showToast(readableError(error));
    }
}

async function createGame(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const playerName = form.elements.whitePlayer.value.trim();
    if (!playerName) return;

    setFormBusy(form, true);
    try {
        const response = await api("/api/v1/games", {
            method: "POST",
            body: { whitePlayer: playerName }
        });
        client.session = {
            gameId: response.game.id,
            token: response.whiteCredential.token,
            side: "WHITE",
            playerName: response.whiteCredential.playerName
        };
        saveSession();
        setSharedGameInUrl(response.game.id);
        enterGame(response.game, "白方已创建对局，等待黑方加入");
        showToast("对局已创建。复制编号并在另一个浏览器窗口中加入。");
    } catch (error) {
        showToast(readableError(error));
    } finally {
        setFormBusy(form, false);
    }
}

async function joinGame(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const playerName = form.elements.blackPlayer.value.trim();
    const gameId = form.elements.gameId.value.trim();
    if (!playerName || !gameId) return;

    setFormBusy(form, true);
    try {
        const response = await api(`/api/v1/games/${encodeURIComponent(gameId)}/join`, {
            method: "POST",
            body: { blackPlayer: playerName }
        });
        client.session = {
            gameId: response.game.id,
            token: response.credential.token,
            side: "BLACK",
            playerName: response.credential.playerName
        };
        saveSession();
        setSharedGameInUrl(response.game.id);
        enterGame(response.game, "黑方已加入，对局正式开始");
    } catch (error) {
        showToast(readableError(error));
    } finally {
        setFormBusy(form, false);
    }
}

function enterGame(game, message) {
    elements.setupPanel.hidden = true;
    elements.gamePanel.hidden = false;
    elements.gamePanel.classList.remove("is-preview");
    elements.copyGameId.disabled = false;
    elements.refreshGame.disabled = false;
    client.logs = [];
    addLog(message);
    applyGame(game, "REST");
    connectRealtime();
}

function showSetup() {
    elements.setupPanel.hidden = false;
    elements.gamePanel.hidden = true;
}

function leaveGame() {
    disconnectRealtime(false);
    sessionStorage.removeItem(STORAGE_KEY);
    client.session = null;
    client.game = null;
    client.selectedSource = null;
    client.logs = [];
    history.replaceState({}, "", window.location.pathname);
    showSetup();
    setConnection("offline", "未加入对局");
    showToast("已离开当前标签页的对局会话");
}

async function refreshGame(reason = "状态刷新") {
    if (!client.session) return;
    try {
        const game = await api(`/api/v1/games/${client.session.gameId}`);
        applyGame(game, "REST");
        addLog(`${reason} · 版本 ${game.version}`);
    } catch (error) {
        showToast(readableError(error));
    }
}

async function handlePositionClick(position) {
    if (!client.session || !client.game || client.pendingAction) return;
    if (client.game.status === "WAITING_FOR_PLAYER") {
        showToast("等待黑方加入后才能开始落子");
        return;
    }
    if (client.game.state.winner) return;
    if (client.game.state.currentPlayer !== client.session.side) {
        showToast("现在是对方回合；对方操作后棋盘会自动更新");
        return;
    }

    const phase = client.game.phase;
    if (phase === "PLACING") {
        if (client.game.legalPlacements.includes(position)) await performAction("PLACE", null, position);
        return;
    }

    if (phase === "REMOVE") {
        if (client.game.removablePieces.includes(position)) await performAction("REMOVE", null, position);
        return;
    }

    const legalMoves = client.game.legalMoves || {};
    if (!client.selectedSource) {
        if (legalMoves[position]) {
            client.selectedSource = position;
            addLog(`已选择 ${position}，请选择目标位置`);
            renderBoard();
        }
        return;
    }

    const destinations = legalMoves[client.selectedSource] || [];
    if (destinations.includes(position)) {
        await performAction("MOVE", client.selectedSource, position);
    } else if (legalMoves[position]) {
        client.selectedSource = position;
        addLog(`已改选 ${position}`);
        renderBoard();
    } else {
        showToast("该位置不是当前棋子的合法目标");
    }
}

async function performAction(type, from, to) {
    const expectedVersion = client.game.version;
    client.pendingAction = true;
    renderBoard();

    try {
        const game = await api(`/api/v1/games/${client.session.gameId}/actions`, {
            method: "POST",
            headers: {
                "X-Player-Token": client.session.token,
                "Idempotency-Key": createIdempotencyKey()
            },
            body: { type, from, to, expectedVersion }
        });
        client.selectedSource = null;
        applyGame(game, "REST");
        const actionText = type === "MOVE" ? `${from} → ${to}` : `${type} ${to}`;
        addLog(`${client.session.side} ${actionText} · 已提交`);
    } catch (error) {
        if (error.code === "VERSION_CONFLICT") await refreshGame("检测到版本冲突，已同步最新状态");
        showToast(readableError(error));
    } finally {
        client.pendingAction = false;
        renderBoard();
    }
}

function applyGame(game, source) {
    const previousVersion = client.game?.version;
    if (previousVersion != null && game.version < previousVersion) return;
    client.game = game;
    if (source === "STOMP" && game.version > (previousVersion ?? -1)) {
        addLog(`收到服务端实时推送 · 版本 ${game.version}`);
    }
    renderGame();
}

function renderGame() {
    if (!client.game || !client.session) return;
    const game = client.game;
    const state = game.state;
    elements.playerSide.textContent = `${client.session.side} · ${client.session.playerName}`;
    elements.gameVersion.textContent = String(game.version);
    elements.gamePhase.textContent = phaseLabel(game.phase);
    elements.currentPlayer.textContent = state.winner ? `${state.winner} 获胜` : state.currentPlayer;
    elements.gameIdText.textContent = game.id;
    elements.whitePlayerName.textContent = game.whitePlayer;
    elements.blackPlayerName.textContent = game.blackPlayer || "等待加入";
    elements.whitePieces.textContent = String(state.whitePiecesToPlace);
    elements.blackPieces.textContent = String(state.blackPiecesToPlace);
    elements.actionPrompt.textContent = actionPrompt();
    renderBoard();
}

function renderBoard() {
    const game = client.game;
    const canAct = Boolean(
        game && client.session && !client.pendingAction && !game.state.winner &&
        game.status !== "WAITING_FOR_PLAYER" && game.state.currentPlayer === client.session.side
    );
    const legal = new Set();
    if (canAct) {
        if (game.phase === "PLACING") game.legalPlacements.forEach(position => legal.add(position));
        else if (game.phase === "REMOVE") game.removablePieces.forEach(position => legal.add(position));
        else if (client.selectedSource) {
            Object.keys(game.legalMoves || {}).forEach(position => legal.add(position));
            (game.legalMoves[client.selectedSource] || []).forEach(position => legal.add(position));
        } else Object.keys(game.legalMoves || {}).forEach(position => legal.add(position));
    }

    for (const button of elements.board.querySelectorAll(".position")) {
        const position = button.dataset.position;
        const piece = game?.state?.board?.[position] || "EMPTY";
        button.dataset.piece = piece;
        button.classList.toggle("is-legal", legal.has(position));
        button.classList.toggle("is-selected", client.selectedSource === position);
        button.disabled = !legal.has(position);
        button.setAttribute("aria-label", `${position}, ${piece.toLowerCase()}${legal.has(position) ? ", legal action" : ""}`);
    }
}

function actionPrompt() {
    const game = client.game;
    if (!game) return "创建或加入对局后即可操作";
    if (game.status === "WAITING_FOR_PLAYER") return "等待 Black 加入；对局编号可在左侧复制";
    if (game.state.winner) return `${game.state.winner} 获胜，对局结束`;
    if (game.state.currentPlayer !== client.session.side) return `等待 ${game.state.currentPlayer} 操作，棋盘将实时更新`;
    if (client.pendingAction) return "正在提交并等待事务确认…";
    if (game.phase === "PLACING") return "轮到你了：选择一个高亮棋位放置棋子";
    if (game.phase === "REMOVE") return "已形成磨：选择一个高亮的对方棋子移除";
    if (client.selectedSource) return `已选择 ${client.selectedSource}：请选择高亮目标位置`;
    return game.phase === "FLYING" ? "飞行阶段：选择棋子后可移动到任意空位" : "选择一个高亮棋子进行移动";
}

function phaseLabel(phase) {
    return ({ PLACING: "放置阶段", MOVING: "移动阶段", FLYING: "飞行阶段", REMOVE: "移除棋子", GAME_OVER: "对局结束" })[phase] || phase;
}

function connectRealtime() {
    disconnectRealtime(false);
    if (!client.session) return;
    client.shouldReconnect = true;
    client.stompBuffer = "";
    setConnection("connecting", "正在连接实时频道");
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(`${protocol}//${window.location.host}/ws`);
    client.socket = socket;

    socket.addEventListener("open", () => {
        if (client.socket !== socket) return;
        sendStomp("CONNECT", { "accept-version": "1.2", "heart-beat": "0,0", host: window.location.host });
    });
    socket.addEventListener("message", event => {
        if (client.socket === socket) receiveStomp(String(event.data));
    });
    socket.addEventListener("close", () => {
        if (client.socket !== socket) return;
        client.socket = null;
        setConnection("offline", "实时连接已断开");
        if (client.shouldReconnect && client.session) {
            clearTimeout(client.reconnectTimer);
            client.reconnectTimer = setTimeout(connectRealtime, 2200);
        }
    });
    socket.addEventListener("error", () => {
        if (client.socket === socket) setConnection("offline", "实时连接异常");
    });
}

function receiveStomp(payload) {
    client.stompBuffer += payload;
    let end = client.stompBuffer.indexOf("\0");
    while (end >= 0) {
        const rawFrame = client.stompBuffer.slice(0, end).replace(/^\n+/, "");
        client.stompBuffer = client.stompBuffer.slice(end + 1);
        if (rawFrame.trim()) handleStompFrame(parseStomp(rawFrame));
        end = client.stompBuffer.indexOf("\0");
    }
    client.stompBuffer = client.stompBuffer.replace(/^\n+/, "");
}

function parseStomp(rawFrame) {
    const normalized = rawFrame.replace(/\r\n/g, "\n");
    const separator = normalized.indexOf("\n\n");
    const headerText = separator >= 0 ? normalized.slice(0, separator) : normalized;
    const body = separator >= 0 ? normalized.slice(separator + 2) : "";
    const lines = headerText.split("\n");
    const command = lines.shift();
    const headers = {};
    for (const line of lines) {
        const colon = line.indexOf(":");
        if (colon > 0) headers[line.slice(0, colon)] = unescapeStomp(line.slice(colon + 1));
    }
    return { command, headers, body };
}

function handleStompFrame(frame) {
    if (frame.command === "CONNECTED") {
        sendStomp("SUBSCRIBE", {
            id: `game-${client.session.gameId}`,
            destination: `/topic/games/${client.session.gameId}`,
            ack: "auto",
            "X-Player-Token": client.session.token
        });
        setConnection("live", "实时同步已连接");
        addLog("STOMP订阅已鉴权并建立");
    } else if (frame.command === "MESSAGE") {
        try { applyGame(JSON.parse(frame.body), "STOMP"); }
        catch { showToast("收到无法解析的实时状态"); }
    } else if (frame.command === "ERROR") {
        setConnection("offline", "订阅鉴权失败");
        showToast(frame.body || frame.headers.message || "STOMP订阅失败");
    }
}

function sendStomp(command, headers, body = "") {
    if (!client.socket || client.socket.readyState !== WebSocket.OPEN) return;
    const headerLines = Object.entries(headers).map(([name, value]) => `${name}:${escapeStomp(String(value))}`).join("\n");
    client.socket.send(`${command}\n${headerLines}\n\n${body}\0`);
}

function disconnectRealtime(sendDisconnect) {
    client.shouldReconnect = false;
    clearTimeout(client.reconnectTimer);
    const socket = client.socket;
    client.socket = null;
    if (!socket) return;
    if (sendDisconnect && socket.readyState === WebSocket.OPEN) socket.send(`DISCONNECT\nreceipt:bye-${Date.now()}\n\n\0`);
    socket.close();
}

function escapeStomp(value) {
    return value.replace(/\\/g, "\\\\").replace(/\r/g, "\\r").replace(/\n/g, "\\n").replace(/:/g, "\\c");
}

function unescapeStomp(value) {
    return value.replace(/\\c/g, ":").replace(/\\n/g, "\n").replace(/\\r/g, "\r").replace(/\\\\/g, "\\");
}

async function api(path, options = {}) {
    const headers = { Accept: "application/json", ...(options.headers || {}) };
    const request = { method: options.method || "GET", headers };
    if (options.body !== undefined) {
        headers["Content-Type"] = "application/json";
        request.body = JSON.stringify(options.body);
    }
    const response = await fetch(path, request);
    const text = await response.text();
    let body = null;
    if (text) {
        try { body = JSON.parse(text); } catch { body = { message: text }; }
    }
    if (!response.ok) {
        const error = new Error(body?.message || `HTTP ${response.status}`);
        error.status = response.status;
        error.code = body?.code;
        error.details = body;
        throw error;
    }
    return body;
}

function addLog(message) {
    client.logs.unshift({ time: new Date(), message });
    client.logs = client.logs.slice(0, MAX_LOG_ITEMS);
    elements.activityLog.replaceChildren();
    for (const item of client.logs) {
        const row = document.createElement("li");
        const time = document.createElement("time");
        const text = document.createElement("span");
        time.textContent = item.time.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
        text.textContent = item.message;
        row.append(time, text);
        elements.activityLog.appendChild(row);
    }
}

function setConnection(state, text) {
    elements.connectionPill.dataset.state = state;
    elements.connectionText.textContent = text;
}

function setFormBusy(form, busy) {
    for (const element of form.elements) element.disabled = busy;
    form.setAttribute("aria-busy", String(busy));
}

function saveSession() { sessionStorage.setItem(STORAGE_KEY, JSON.stringify(client.session)); }

function setSharedGameInUrl(gameId) {
    const url = new URL(window.location.href);
    url.searchParams.set("game", gameId);
    history.replaceState({}, "", url);
}

async function copyGameId() {
    if (!client.session) return;
    try {
        await navigator.clipboard.writeText(client.session.gameId);
        showToast("对局编号已复制，可在另一浏览器窗口中加入");
    } catch {
        showToast(`请手动复制：${client.session.gameId}`);
    }
}

function createIdempotencyKey() {
    if (window.crypto?.randomUUID) return window.crypto.randomUUID();
    return `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function readableError(error) {
    return error?.code ? `${error.code}: ${error.message}` : error?.message || "请求失败，请确认后端正在运行";
}

let toastTimer;
function showToast(message) {
    clearTimeout(toastTimer);
    elements.toast.textContent = message;
    elements.toast.classList.add("is-visible");
    toastTimer = setTimeout(() => elements.toast.classList.remove("is-visible"), 4200);
}

window.addEventListener("beforeunload", () => disconnectRealtime(true));
