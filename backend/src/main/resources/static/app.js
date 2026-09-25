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
const IDENTITY_STORAGE_KEY = "morris.player.v1";
const MAX_LOG_ITEMS = 8;

let inMemoryIdentity = null;
let identityStorageWarned = false;

function warnIdentityStorageOnce(message, error) {
    console.warn(message, error);
    if (!identityStorageWarned) {
        identityStorageWarned = true;
        try { showToast("无法保存本地身份，本标签页关闭后需重新加入。"); } catch { /* toast not ready yet */ }
    }
}

function saveIdentity(identity) {
    inMemoryIdentity = identity;
    try {
        localStorage.setItem(IDENTITY_STORAGE_KEY, JSON.stringify(identity));
    } catch (error) {
        warnIdentityStorageOnce("Could not persist player identity; it will not survive closing this tab.", error);
    }
}

function loadIdentity() {
    try {
        const raw = localStorage.getItem(IDENTITY_STORAGE_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch (error) {
        warnIdentityStorageOnce("Could not read player identity from storage (private browsing?).", error);
        return inMemoryIdentity;
    }
}

function clearIdentity() {
    inMemoryIdentity = null;
    try {
        localStorage.removeItem(IDENTITY_STORAGE_KEY);
    } catch (error) {
        console.warn("Could not clear stored player identity.", error);
    }
}

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
    toast: document.getElementById("toast"),
    roomCodeText: document.getElementById("roomCodeText"),
    copyRoomCode: document.getElementById("copyRoomCode"),
    myGamesList: document.getElementById("my-games-list"),
    roomCodeInput: document.getElementById("roomCodeInput"),
    roomCodeJoinButton: document.getElementById("roomCodeJoinButton"),
    whiteClock: document.getElementById("whiteClock"),
    blackClock: document.getElementById("blackClock"),
    clockGrace: document.getElementById("clockGrace"),
    opponentPresence: document.getElementById("opponentPresence"),
    resultReason: document.getElementById("resultReason"),
    gameActions: document.getElementById("gameActions"),
    cancelButton: document.getElementById("cancelButton"),
    resignButton: document.getElementById("resignButton"),
    drawOfferButton: document.getElementById("drawOfferButton"),
    drawAcceptButton: document.getElementById("drawAcceptButton"),
    drawDeclineButton: document.getElementById("drawDeclineButton"),
    drawStatusText: document.getElementById("drawStatusText"),
    rematchButton: document.getElementById("rematchButton"),
    rematchAcceptButton: document.getElementById("rematchAcceptButton"),
    rematchDeclineButton: document.getElementById("rematchDeclineButton"),
    rematchStatusText: document.getElementById("rematchStatusText")
};

const client = {
    session: null,
    game: null,
    socket: null,
    subscribed: false,
    realtimeRun: null,
    reconnectTimer: null,
    connectionTimer: null,
    epoch: 0,
    entryBusy: false,
    retryBusy: false,
    active: false,
    pollTimer: null,
    pendingJoin: null,
    selectedSource: null,
    pendingAction: false,
    lastChanged: [],
    logs: [],
    identity: null,
    roomCode: null,
    clockTimer: null,
    clockSkewMs: 0,
    presence: null
};

initializeBoard();
bindEvents();
prefillSharedGame();
restoreSession();
// Bound to the real "load" event only; the Node test harness's window.addEventListener stub is a
// no-op, so this network-touching bootstrap never runs inside the frontend unit test sandbox.
window.addEventListener("load", ensureIdentity);

function initializeBoard() {
    for (const position of POSITIONS) {
        const point = document.createElement("button");
        point.type = "button";
        point.className = "position";
        point.dataset.position = position;
        point.dataset.piece = "EMPTY";
        point.setAttribute("aria-label", `${position}，空位`);
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
    document.getElementById("recoverSession").addEventListener("click", restoreSession);
    document.getElementById("clearSession").addEventListener("click", clearSession);
    document.getElementById("retryAction").addEventListener("click", retryAction);
    elements.refreshGame.addEventListener("click", () => refreshGame("手动刷新"));
    elements.copyRoomCode.addEventListener("click", copyRoomCode);
    elements.roomCodeJoinButton.addEventListener("click", lookupRoomCode);
    elements.cancelButton.addEventListener("click", () => void cancelGame());
    elements.resignButton.addEventListener("click", () => { if (window.confirm("确定要认输吗？")) void resign(); });
    elements.drawOfferButton.addEventListener("click", () => void offerDraw("OFFER"));
    elements.drawAcceptButton.addEventListener("click", () => void offerDraw("ACCEPT"));
    elements.drawDeclineButton.addEventListener("click", () => void offerDraw("DECLINE"));
    elements.rematchButton.addEventListener("click", () => void offerRematch("OFFER"));
    elements.rematchAcceptButton.addEventListener("click", () => void offerRematch("ACCEPT"));
    elements.rematchDeclineButton.addEventListener("click", () => void offerRematch("DECLINE"));
}

function prefillSharedGame() {
    const sharedId = new URLSearchParams(window.location.search).get("game");
    if (sharedId) elements.joinGameId.value = sharedId;
}

function loadSaved() {
    try {
        const value = JSON.parse(sessionStorage.getItem(STORAGE_KEY));
        return value?.gameId ? { session: value } : (value || {});
    } catch { return {}; }
}

function context() { return { epoch: client.epoch, gameId: client.session?.gameId }; }
function matches(ctx) { return ctx.epoch === client.epoch && ctx.gameId === client.session?.gameId; }
function transient(error) { return !error.status || error.status >= 500 || error.status === 408 || error.status === 429; }
function entryBusy(busy) {
    client.entryBusy = busy;
    setFormBusy(elements.createForm, busy);
    setFormBusy(elements.joinForm, busy);
    document.getElementById("recoverSession").disabled = busy;
    document.getElementById("clearSession").disabled = busy;
}

async function restoreSession() {
    if (client.entryBusy) return;
    const saved = loadSaved();
    client.session = saved.session || null;
    client.pendingJoin = saved.pendingJoin || null;
    client.pendingAction = saved.pendingAction || null;
    client.roomCode = saved.roomCode || null;
    client.epoch++;
    client.game = null;
    if (client.pendingJoin) return resumeJoin();
    if (!client.session?.gameId || !client.session?.token || !client.session?.side) {
        showSetup();
        return;
    }
    const ctx = context();
    entryBusy(true);
    try {
        const game = await api(`/api/v1/games/${ctx.gameId}/session`, {
            headers: credentialHeaders()
        });
        if (!matches(ctx)) return;
        enterGame(game, "已恢复当前标签页的玩家身份");
    } catch (error) {
        if (!matches(ctx)) return;
        showSetup();
        const text = error.code === "GAME_NOT_FOUND" ? "对局不存在，请确认服务连接或明确清除身份。"
            : error.code === "INVALID_PLAYER_TOKEN" ? "玩家凭证已失效，可明确清除身份后重新加入。"
            : "暂时无法恢复，身份已保留，请点击恢复重试。";
        showToast(text);
    } finally { if (matches(ctx)) entryBusy(false); }
}

function mayStartEntry() {
    if (client.entryBusy) return false;
    const saved = loadSaved();
    if (saved.session || saved.pendingJoin) {
        showToast("本标签页已有身份，请先恢复；需要更换对局时，请明确清除保存的身份。");
        return false;
    }
    client.epoch++;
    client.game = null;
    return true;
}

async function createGame(event) {
    event.preventDefault();
    if (!mayStartEntry()) return;
    const playerName = event.currentTarget.elements.whitePlayer.value.trim();
    if (!playerName) return;
    const timeControl = event.currentTarget.elements.timeControl?.value || "5+3";
    const ctx = context();
    entryBusy(true);
    try {
        const options = client.identity
            ? { method: "POST", headers: { ...authHeader(client.identity.clientToken), "Idempotency-Key": randomToken() }, body: { timeControl } }
            : { method: "POST", body: { whitePlayer: playerName } };
        const response = await api("/api/v1/games", options);
        if (!matches(ctx)) return;
        // A Bearer-authenticated create never returns a whiteCredential (the player's own bearer
        // token already authenticates every later request for this game); only the legacy
        // anonymous branch mints one.
        client.session = client.identity
            ? { gameId: response.game.id, token: client.identity.clientToken, side: "WHITE", playerName: client.identity.nickname, bearer: true }
            : { gameId: response.game.id, token: response.whiteCredential.token, side: "WHITE", playerName: response.whiteCredential.playerName };
        client.roomCode = response.roomCode || null;
        saveSession();
        enterGame(response.game, "白方已创建对局，复制邀请链接邀请黑方加入");
        if (client.identity) void renderMyGames();
    } catch (error) { if (ctx.epoch === client.epoch) showToast(readableError(error)); }
    finally { if (ctx.epoch === client.epoch) entryBusy(false); }
}

async function joinGame(event) {
    event.preventDefault();
    if (!mayStartEntry()) return;
    const form = event.currentTarget;
    const blackPlayer = form.elements.blackPlayer.value.trim();
    const gameId = form.elements.gameId.value.trim().toLowerCase();
    if (!blackPlayer || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(gameId)) {
        showToast("请输入玩家名称和有效的对局编号"); return;
    }
    if (client.identity) {
        // Identity-aware path: same Bearer POST /join used by the room-code flow
        // (joinRoomAsIdentity), so the game is recorded against this player and shows up in this
        // browser's own "我的对局" list. The legacy anonymous body-based join below is now only a
        // fallback for tabs with no saved identity - and the backend rejects it outright against a
        // Bearer-created game (GameSessionService.join), so it must not be the default any more.
        await joinRoomAsIdentity(gameId);
        return;
    }
    try {
        client.pendingJoin = { gameId, blackPlayer, joinToken: randomToken() };
        saveSession(); // Persist the proof BEFORE sending a request that may claim the seat.
    } catch { client.pendingJoin = null; showToast("无法保存加入凭证，请检查浏览器存储权限。"); return; }
    await resumeJoin();
}

async function resumeJoin() {
    const pending = client.pendingJoin;
    const ctx = context();
    entryBusy(true);
    try {
        const response = await api(`/api/v1/games/${pending.gameId}/join`, {
            method: "POST", body: { blackPlayer: pending.blackPlayer, joinToken: pending.joinToken }
        });
        if (!matches(ctx)) return;
        client.session = { gameId: response.game.id, token: pending.joinToken,
            side: "BLACK", playerName: response.credential.playerName };
        client.pendingJoin = null;
        client.roomCode = null;
        saveSession();
        enterGame(response.game, "黑方席位已确认，已恢复最新状态");
    } catch (error) {
        if (!matches(ctx)) return;
        showSetup();
        showToast(transient(error) ? "加入结果暂未确认，凭证已保存。点击恢复可安全重试。" : readableError(error));
    } finally { if (ctx.epoch === client.epoch) entryBusy(false); }
}

function clearSession() {
    if (client.entryBusy) return;
    if (!window.confirm("永久清除本标签页的玩家凭证和未确认请求？之后无法仅凭姓名恢复身份。")) return;
    leaveGame();
    sessionStorage.removeItem(STORAGE_KEY);
    client.session = null;
    client.pendingJoin = null;
    client.pendingAction = null;
    client.roomCode = null;
    showSetup();
    setConnection("offline", "尚未进入对局");
    showToast("保存的身份已明确清除");
}

function enterGame(game, message) {
    client.active = true;
    document.getElementById("inviteLink").hidden = true;
    document.getElementById("inviteLink").value = "";
    setSharedGameInUrl(game.id);
    elements.setupPanel.hidden = true;
    elements.gamePanel.hidden = false;
    elements.gamePanel.classList.remove("is-preview");
    elements.copyGameId.disabled = false;
    elements.refreshGame.disabled = false;
    elements.roomCodeText.textContent = client.roomCode || "仅创建对局时可见";
    elements.copyRoomCode.disabled = !client.roomCode;
    client.logs = [];
    addLog(message);
    applyGame(game, "REST");
    connectRealtime();
}

function showSetup() {
    const saved = loadSaved();
    document.getElementById("recoveryCard").hidden = !(saved.session || saved.pendingJoin);
    elements.setupPanel.hidden = false;
    elements.gamePanel.hidden = true;
}

function leaveGame() {
    client.epoch++;
    client.active = false;
    client.retryBusy = false;
    disconnectRealtime();
    stopClockLoop();
    client.game = null;
    client.selectedSource = null;
    client.logs = [];
    client.lastChanged = [];
    client.presence = null;
    renderPresence();
    history.replaceState({}, "", window.location.pathname);
    showSetup();
    setConnection("offline", "身份已保存，可恢复对局");
}

async function refreshGame(reason = "状态刷新") {
    if (!client.session || !client.active) return;
    const ctx = context();
    try {
        const game = await api(`/api/v1/games/${ctx.gameId}/session`, {
            headers: credentialHeaders()
        });
        if (!matches(ctx) || !client.active) return;
        applyGame(game, "REST");
        setConnection(client.subscribed ? "live" : "snapshot", client.subscribed ? "实时已连接 · 已同步" : "快照已同步 · 实时未连接");
        if (reason) addLog(`${reason} · 版本 ${game.version}`);
    } catch (error) {
        if (!matches(ctx) || !client.active) return;
        setConnection("offline", "同步暂不可用，身份已保留");
        if (reason) showToast(readableError(error));
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
            renderGame();
        }
        return;
    }

    const destinations = legalMoves[client.selectedSource] || [];
    if (destinations.includes(position)) {
        await performAction("MOVE", client.selectedSource, position);
    } else if (legalMoves[position]) {
        client.selectedSource = position;
        addLog(`已改选 ${position}`);
        renderGame();
    } else {
        showToast("该位置不是当前棋子的合法目标");
    }
}

async function performAction(type, from, to) {
    if (client.pendingAction || !client.active || !client.game) return;
    client.pendingAction = { gameId: client.session.gameId, key: createIdempotencyKey(),
        body: { type, from, to, expectedVersion: client.game.version } };
    try { saveSession(); }
    catch { client.pendingAction = null; showToast("无法保存请求，操作未发送。"); return; }
    await retryAction();
}

async function retryAction() {
    const pending = client.pendingAction;
    if (!pending || client.retryBusy || !client.active || pending.gameId !== client.session?.gameId) return;
    const ctx = context();
    client.retryBusy = true;
    renderGame();
    try {
        for (let attempt = 0; attempt < 3; attempt++) {
            if (!matches(ctx) || !client.active) return;
            try {
                const game = await api(`/api/v1/games/${pending.gameId}/actions`, {
                    method: "POST", headers: { ...credentialHeaders(), "Idempotency-Key": pending.key }, body: pending.body
                });
                if (!matches(ctx)) return;
                client.pendingAction = null;
                saveSession();
                client.selectedSource = null;
                applyGame(game, "REST");
                return;
            } catch (error) {
                if (!matches(ctx)) return;
                const uncertain = transient(error) || error.code === "CONCURRENT_REQUEST_CONFLICT";
                if (!uncertain) {
                    client.pendingAction = null;
                    saveSession();
                    if (error.code === "VERSION_CONFLICT") await refreshGame("操作版本过期，已同步");
                    showToast(readableError(error));
                    return;
                }
                if (attempt === 2) { showToast("结果尚未确认，已保留原请求。请稍后点击重试未确认操作。"); return; }
                await new Promise(resolve => setTimeout(resolve, 500 * (attempt + 1)));
            }
        }
    } finally {
        if (matches(ctx)) { client.retryBusy = false; renderGame(); }
    }
}

function applyGame(game, source) {
    if (!client.active || game?.id !== client.session?.gameId) return;
    const previousVersion = client.game?.version;
    const previousRematchGameId = client.game?.rematchGameId;
    if (previousVersion != null && game.version < previousVersion) return;
    if (previousVersion != null && game.version > previousVersion) {
        const changes = POSITIONS.filter(p => (client.game.state.board[p] || "EMPTY") !== (game.state.board[p] || "EMPTY"));
        // A skipped version may contain several moves; do not imply one latest move.
        client.lastChanged = game.version === previousVersion + 1 ? changes : [];
        client.selectedSource = null;
    } else if (previousVersion == null) client.lastChanged = [];
    client.game = game;
    if (game.clock && game.clock.serverNow) {
        client.clockSkewMs = Date.now() - Date.parse(game.clock.serverNow);
    }
    if (source === "WEBSOCKET" && game.version > (previousVersion ?? -1)) {
        addLog(`收到服务端实时推送 · 版本 ${game.version}`);
    }
    // A rematch that just became available (rematchGameId only appears once the new game exists)
    // takes both windows straight into the new game rather than leaving them on the finished one.
    if (game.rematchGameId && !previousRematchGameId) {
        void enterMyGame(game.rematchGameId);
        return;
    }
    renderGame();
}

function renderGame() {
    if (!client.game || !client.session) return;
    const game = client.game;
    const state = game.state;
    document.getElementById("retryAction").hidden = !client.pendingAction;
    document.getElementById("retryAction").disabled = client.retryBusy;
    elements.playerSide.textContent = `${sideLabel(client.session.side)} · ${client.session.playerName}`;
    elements.gameVersion.textContent = String(game.version);
    elements.gamePhase.textContent = phaseLabel(game.phase);
    elements.currentPlayer.textContent = state.winner ? `${sideLabel(state.winner)} 获胜` : sideLabel(state.currentPlayer);
    elements.gameIdText.textContent = game.id;
    elements.whitePlayerName.textContent = game.whitePlayer;
    elements.blackPlayerName.textContent = game.blackPlayer || "等待加入";
    elements.whitePieces.textContent = String(state.whitePiecesToPlace);
    elements.blackPieces.textContent = String(state.blackPiecesToPlace);
    for (const side of ["WHITE", "BLACK"]) {
        const prefix = side.toLowerCase();
        document.getElementById(prefix + "OnBoard").textContent = String(Object.values(state.board).filter(piece => piece === side).length);
        const isTurn = !state.winner && game.status !== "WAITING_FOR_PLAYER" && state.currentPlayer === side;
        document.getElementById(prefix + "Card").classList.toggle("is-turn", isTurn);
        document.getElementById(prefix + "Role").textContent = sideLabel(side) + (client.session.side === side ? " · 你" : "") + (isTurn ? " · 当前回合" : "");
    }
    const notice = document.getElementById("operationNotice");
    notice.hidden = !client.pendingAction;
    notice.textContent = client.retryBusy ? "正在确认操作，请稍候…" : "上次操作结果尚未确认。请重试原请求，确认前不能继续落子。";
    elements.actionPrompt.textContent = actionPrompt();
    renderBoard();
    renderActions();
    renderResult();
    renderPresence();
    ensureClockLoop();
    renderClock();
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
        button.classList.toggle("is-target", legal.has(position) && piece === "EMPTY");
        button.classList.toggle("is-source", legal.has(position) && piece !== "EMPTY");
        button.classList.toggle("is-last-change", client.lastChanged.includes(position));
        button.disabled = !legal.has(position);
        button.setAttribute("aria-label", `${position}，${piece === "EMPTY" ? "空位" : sideLabel(piece)}${legal.has(position) ? "，可操作" : ""}`);
    }
}

function actionPrompt() {
    const game = client.game;
    if (!game) return "创建或加入对局后即可操作";
    if (client.pendingAction) return client.retryBusy ? "正在确认你的操作…" : "操作结果未确认，请点击重试";
    if (game.status === "WAITING_FOR_PLAYER") return "等待黑方加入，复制邀请链接发给朋友";
    if (game.state.winner) return `${sideLabel(game.state.winner)}获胜，对局结束`;
    if (game.state.currentPlayer !== client.session.side) return `等待${sideLabel(game.state.currentPlayer)}操作，棋盘会自动同步`;
    if (client.pendingAction) return "有未确认操作，请等待或点击重试";
    if (game.phase === "PLACING") return "轮到你了：选择一个高亮棋位放置棋子";
    if (game.phase === "REMOVE") return "已形成磨：选择一个高亮的对方棋子移除";
    if (client.selectedSource) return `已选择 ${client.selectedSource}：请选择高亮目标位置`;
    return game.phase === "FLYING" ? "飞行阶段：选择棋子后可移动到任意空位" : "选择一个高亮棋子进行移动";
}

// Pure, testable clock math (spec M2.3). formatClock never renders a negative duration.
function formatClock(ms) {
    const clamped = Math.max(0, ms);
    const totalSeconds = Math.floor(clamped / 1000);
    const minutes = String(Math.floor(totalSeconds / 60)).padStart(2, "0");
    const seconds = String(totalSeconds % 60).padStart(2, "0");
    return `${minutes}:${seconds}`;
}

// Given a ClockView snapshot/push, the game state (for the piecesToPlace first-move check) and
// which side is to move, returns what each side's clock (and, for a first move, its separate
// grace countdown) should show right now. skewMs must be (localNowMs - Date.parse(serverNow))
// captured at the moment the snapshot/push that produced `clock` arrived - the caller corrects
// for it here as correctedNow = localNowMs - skewMs, per spec M2.3.
function computeClockDisplay(clock, gameState, sideToMove, localNowMs, skewMs) {
    const white = { ms: clock?.whiteMs ?? 0, graceMs: null };
    const black = { ms: clock?.blackMs ?? 0, graceMs: null };
    if (!clock || !clock.running || !clock.turnDeadlineAt) {
        return { white, black };
    }
    const correctedNow = localNowMs - (skewMs || 0);
    const deadlineMs = Date.parse(clock.turnDeadlineAt);
    const remaining = Math.max(0, deadlineMs - correctedNow);
    const target = sideToMove === "WHITE" ? white : black;
    const firstMove = sideToMove === "WHITE"
        ? gameState?.whitePiecesToPlace === 9
        : gameState?.blackPiecesToPlace === 9;
    if (firstMove) target.graceMs = remaining;
    else target.ms = remaining;
    return { white, black };
}

function ensureClockLoop() {
    const shouldRun = client.active && client.game && client.game.status === "IN_PROGRESS";
    if (shouldRun && !client.clockTimer) {
        client.clockTimer = setInterval(renderClock, 100); // spec M2.3: refresh every 100ms
    } else if (!shouldRun && client.clockTimer) {
        stopClockLoop();
    }
}

function stopClockLoop() {
    if (client.clockTimer) {
        clearInterval(client.clockTimer);
        client.clockTimer = null;
    }
}

function renderClock() {
    const game = client.game;
    if (!game || !elements.whiteClock || !elements.blackClock) return;
    const clock = game.clock;
    const hasClock = Boolean(clock && (clock.running || clock.turnDeadlineAt || clock.whiteMs || clock.blackMs));
    if (!hasClock) {
        elements.whiteClock.textContent = "--:--";
        elements.blackClock.textContent = "--:--";
        if (elements.clockGrace) elements.clockGrace.hidden = true;
        return;
    }
    const display = computeClockDisplay(clock, game.state, game.state.currentPlayer, Date.now(), client.clockSkewMs);
    elements.whiteClock.textContent = formatClock(display.white.ms);
    elements.blackClock.textContent = formatClock(display.black.ms);
    if (elements.clockGrace) {
        const graceMs = display.white.graceMs ?? display.black.graceMs;
        if (graceMs != null) {
            elements.clockGrace.hidden = false;
            elements.clockGrace.textContent = `首步 ${Math.ceil(graceMs / 1000)}s`;
        } else {
            elements.clockGrace.hidden = true;
        }
    }
}

function renderResult() {
    const box = elements.resultReason;
    if (!box) return;
    const result = client.game?.result;
    if (result && result.reason) {
        box.hidden = false;
        box.textContent = resultReasonLabel(result.reason);
    } else {
        box.hidden = true;
    }
}

function renderActions() {
    const box = elements.gameActions;
    if (!box) return;
    const toggles = [elements.cancelButton, elements.resignButton, elements.drawOfferButton,
        elements.drawAcceptButton, elements.drawDeclineButton, elements.drawStatusText,
        elements.rematchButton, elements.rematchAcceptButton, elements.rematchDeclineButton, elements.rematchStatusText];
    for (const el of toggles) if (el) el.hidden = true;
    const game = client.game;
    const session = client.session;
    // Resign/cancel/draw/rematch are identity-only; legacy anonymous sessions never carry a bearer.
    const isIdentity = Boolean(session?.bearer);
    if (!isIdentity || !game) {
        box.hidden = true;
        return;
    }
    box.hidden = false;
    const mySideVal = session.side;
    if (game.status === "WAITING_FOR_PLAYER") {
        if (mySideVal === "WHITE" && elements.cancelButton) elements.cancelButton.hidden = false;
        else box.hidden = true;
    } else if (game.status === "IN_PROGRESS") {
        if (elements.resignButton) elements.resignButton.hidden = false;
        if (game.drawOfferedBy && game.drawOfferedBy !== mySideVal) {
            if (elements.drawAcceptButton) elements.drawAcceptButton.hidden = false;
            if (elements.drawDeclineButton) elements.drawDeclineButton.hidden = false;
        } else if (game.drawOfferedBy === mySideVal) {
            if (elements.drawStatusText) elements.drawStatusText.hidden = false;
        } else if (elements.drawOfferButton) {
            elements.drawOfferButton.hidden = false;
        }
    } else if (game.status === "WHITE_WON" || game.status === "BLACK_WON" || game.status === "DRAWN") {
        if (game.rematchOfferedBy && game.rematchOfferedBy !== mySideVal) {
            if (elements.rematchAcceptButton) elements.rematchAcceptButton.hidden = false;
            if (elements.rematchDeclineButton) elements.rematchDeclineButton.hidden = false;
        } else if (game.rematchOfferedBy === mySideVal) {
            if (elements.rematchStatusText) elements.rematchStatusText.hidden = false;
        } else if (elements.rematchButton) {
            elements.rematchButton.hidden = false;
        }
    } else {
        box.hidden = true;
    }
}

function updatePresence(white, black) {
    client.presence = { WHITE: white, BLACK: black };
    renderPresence();
}

function renderPresence() {
    if (!elements.opponentPresence) return;
    if (!client.session || !client.presence) {
        elements.opponentPresence.textContent = "—";
        return;
    }
    const opponentSide = client.session.side === "WHITE" ? "BLACK" : "WHITE";
    const state = client.presence[opponentSide];
    elements.opponentPresence.textContent = state === "OFFLINE" ? "对手已离线，棋钟仍在计时"
        : state === "ONLINE" ? "对手在线" : "—";
}

async function resign() {
    await runGameAction(() => api(`/api/v1/games/${client.game.id}/resign`, {
        method: "POST",
        headers: { ...credentialHeaders(), "Idempotency-Key": randomToken() }
    }));
}

async function cancelGame() {
    await runGameAction(() => api(`/api/v1/games/${client.game.id}/cancel`, {
        method: "POST",
        headers: { ...credentialHeaders(), "Idempotency-Key": randomToken() }
    }));
}

async function offerDraw(action) {
    await runGameAction(() => api(`/api/v1/games/${client.game.id}/draw`, {
        method: "POST",
        headers: { ...credentialHeaders(), "Idempotency-Key": randomToken() },
        body: { action }
    }));
}

async function offerRematch(action) {
    await runGameAction(() => api(`/api/v1/games/${client.game.id}/rematch`, {
        method: "POST",
        headers: { ...credentialHeaders(), "Idempotency-Key": randomToken() },
        body: { action }
    }));
}

// Shared wrapper for resign/cancel/draw/rematch: applies the returned GameResponse like any other
// REST response, and per the M2 controller ruling, a 409 (the game already ended, e.g. the mover's
// clock ran out first) means the local copy is stale, so it refreshes instead of just toasting.
async function runGameAction(request) {
    if (!client.session || !client.game || client.pendingAction) return;
    const ctx = context();
    try {
        const game = await request();
        if (!matches(ctx)) return;
        applyGame(game, "REST");
    } catch (error) {
        if (!matches(ctx)) return;
        showToast(readableError(error));
        if (error.status === 409) await refreshGame("操作被拒绝，已同步最新状态");
    }
}

function sideLabel(side) { return side === "WHITE" ? "白方" : "黑方"; }

function phaseLabel(phase) {
    return ({ PLACING: "放置阶段", MOVING: "移动阶段", FLYING: "飞行阶段", REMOVE: "移除棋子", GAME_OVER: "对局结束" })[phase] || phase;
}

function connectRealtime() {
    disconnectRealtime();
    if (!client.session || !client.active) return;
    const ctx = context();
    const run = {};
    client.realtimeRun = run;
    const current = () => matches(ctx) && client.active && client.realtimeRun === run;
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    let retryAllowed = true;

    const scheduleReconnect = () => {
        if (!current() || !retryAllowed) return;
        clearTimeout(client.reconnectTimer);
        client.reconnectTimer = setTimeout(openSocket, 2200);
    };
    const openSocket = () => {
        if (!current()) return;
        client.subscribed = false;
        setConnection("connecting", "正在连接实时频道");
        let socket;
        try { socket = new WebSocket(`${protocol}//${window.location.host}/ws`); }
        catch { scheduleReconnect(); return; }
        client.socket = socket;
        let rejectionCode = null;
        const sameSocket = () => current() && client.socket === socket;
        client.connectionTimer = setTimeout(() => {
            if (sameSocket() && !client.subscribed) socket.close();
        }, 8000);

        socket.addEventListener("open", () => {
            if (!sameSocket()) return;
            setConnection("connecting", "连接已建立，正在验证玩家身份");
            socket.send(JSON.stringify({ type: "SUBSCRIBE", gameId: ctx.gameId, token: client.session.token }));
        });
        socket.addEventListener("message", event => {
            if (!sameSocket()) return;
            let message;
            try { message = JSON.parse(event.data); }
            catch { socket.close(); return; }
            if (message?.type === "SUBSCRIBED" && message.gameId === ctx.gameId) {
                client.subscribed = true;
                clearTimeout(client.connectionTimer);
                // Server registers the connection before confirming. Read after confirmation
                // to cover earlier commits, and keep polling to recover later missed pushes.
                void refreshGame("");
            } else if (message?.type === "GAME_STATE" && client.subscribed) {
                if (message.game?.id === ctx.gameId && Number.isSafeInteger(message.game.version)) {
                    applyGame(message.game, "WEBSOCKET");
                }
            } else if (message?.type === "PRESENCE") {
                // Deliberately NOT gated on client.subscribed: the server may send the initial
                // presence snapshot before the SUBSCRIBED ack for this same connection.
                updatePresence(message.white, message.black);
            } else if (message?.type === "ERROR") {
                rejectionCode = message.code;
                retryAllowed = message.code === "INTERNAL_ERROR" || message.code === "AUTH_TIMEOUT";
                client.subscribed = false;
                setConnection("offline", "实时订阅失败，使用快照同步");
                showToast(retryAllowed ? "实时连接暂不可用，将重试" : "实时订阅被拒绝，请检查并恢复玩家身份");
                socket.close();
            }
        });
        socket.addEventListener("close", event => {
            if (!sameSocket()) return;
            clearTimeout(client.connectionTimer);
            client.subscribed = false;
            if (event.code === 1008 && rejectionCode !== "AUTH_TIMEOUT") retryAllowed = false;
            setConnection("offline", retryAllowed ? "实时连接断开，快照同步继续" : "实时订阅被拒绝，快照同步继续");
            scheduleReconnect();
        });
        socket.addEventListener("error", () => {
            if (sameSocket()) socket.close();
        });
    };

    openSocket();
    // This fallback remains active even while reconnecting or after subscription rejection.
    // Recursive timeouts avoid overlapping periodic reads and reject old connection runs.
    const poll = async () => {
        if (!current()) return;
        await refreshGame("");
        if (current()) client.pollTimer = setTimeout(poll, 2000);
    };
    client.pollTimer = setTimeout(poll, 2000);
}

function disconnectRealtime() {
    client.realtimeRun = null;
    client.subscribed = false;
    clearTimeout(client.pollTimer);
    clearTimeout(client.reconnectTimer);
    clearTimeout(client.connectionTimer);
    const socket = client.socket;
    client.socket = null;
    if (socket) socket.close();
}

async function api(path, options = {}) {
    const headers = { Accept: "application/json", ...(options.headers || {}) };
    const request = { method: options.method || "GET", headers };
    if (options.body !== undefined) {
        headers["Content-Type"] = "application/json";
        request.body = JSON.stringify(options.body);
    }
    const controller = new AbortController();
    request.signal = controller.signal;
    const timeout = setTimeout(() => controller.abort(), 8000);
    try {
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
    } finally { clearTimeout(timeout); }
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
    const badge = document.getElementById("realtimeBadge");
    badge.dataset.state = state;
    badge.textContent = state === "live" ? "实时连接" : state === "connecting" ? "连接中" : state === "snapshot" ? "快照同步" : "未连接";
}

function setFormBusy(form, busy) {
    for (const element of form.elements) element.disabled = busy;
    form.setAttribute("aria-busy", String(busy));
}

function saveSession() {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ session: client.session,
        pendingJoin: client.pendingJoin, pendingAction: client.pendingAction, roomCode: client.roomCode }));
}

function setSharedGameInUrl(gameId) {
    const url = new URL(window.location.href);
    url.searchParams.set("game", gameId);
    history.replaceState({}, "", url);
}

async function copyGameId() {
    if (!client.session) return;
    const ctx = context();
    const url = new URL(window.location.pathname, window.location.href);
    url.searchParams.set("game", ctx.gameId);
    const field = document.getElementById("inviteLink");
    field.value = url.href;
    field.hidden = false;
    try {
        await navigator.clipboard.writeText(url.href);
        if (matches(ctx) && client.active) showToast("邀请链接已复制，发给朋友即可加入");
    } catch {
        if (matches(ctx) && client.active) { field.focus(); field.select(); showToast("请复制已选中的邀请链接"); }
    }
}

function authHeader(token) {
    return { Authorization: `Bearer ${token}` };
}

function credentialHeaders() {
    return client.session?.bearer ? authHeader(client.session.token) : { "X-Player-Token": client.session.token };
}

async function ensureIdentity() {
    client.identity = loadIdentity();
    if (client.identity) {
        try {
            const me = await api("/api/v1/players/me", { headers: authHeader(client.identity.clientToken) });
            client.identity = { playerId: me.playerId, nickname: me.nickname, clientToken: client.identity.clientToken };
            saveIdentity(client.identity);
        } catch (error) {
            if (error.status === 401) {
                // 身份已失效：按规范清除保存的身份，随后回落到创建新身份。
                clearIdentity();
                client.identity = null;
            } else {
                // Offline or transient failure: keep the identity we already have rather than
                // silently discarding it, and skip the my-games refresh until it works again.
                if (client.identity) void renderMyGames();
                return;
            }
        }
    }
    if (!client.identity) {
        const nameField = elements.createForm.elements.whitePlayer;
        const nickname = (nameField?.value || "").trim() || `玩家${Math.floor(Math.random() * 10000)}`;
        const clientToken = randomToken();
        try {
            const created = await api("/api/v1/players", { method: "POST", body: { nickname, clientToken } });
            client.identity = { playerId: created.playerId, nickname: created.nickname, clientToken };
            saveIdentity(client.identity);
        } catch (error) {
            showToast("无法创建玩家身份，我的对局暂不可用：" + readableError(error));
            return;
        }
    }
    const createNameField = elements.createForm.elements.whitePlayer;
    if (createNameField) createNameField.value = client.identity.nickname;
    const joinNameField = elements.joinForm.elements.blackPlayer;
    if (joinNameField) joinNameField.value = client.identity.nickname;
    void renderMyGames();
}

async function renderMyGames() {
    if (!client.identity || !elements.myGamesList) return;
    try {
        const result = await api("/api/v1/players/me/games?status=ACTIVE", { headers: authHeader(client.identity.clientToken) });
        elements.myGamesList.replaceChildren();
        for (const summary of result.games || []) {
            const item = document.createElement("li");
            const link = document.createElement("a");
            link.href = `?game=${summary.gameId}`;
            link.textContent = `${summary.opponentNickname || "等待对手"} · ${gameStatusLabel(summary.status)}`;
            link.addEventListener("click", event => {
                event.preventDefault();
                void enterMyGame(summary.gameId);
            });
            item.appendChild(link);
            elements.myGamesList.appendChild(item);
        }
    } catch (error) {
        console.warn("Could not load my-games list.", error);
    }
}

function gameStatusLabel(status) {
    return ({
        WAITING_FOR_PLAYER: "等待对手",
        IN_PROGRESS: "进行中",
        WHITE_WON: "白方胜",
        BLACK_WON: "黑方胜",
        DRAWN: "和棋",
        ABORTED: "已放弃（首步超时）",
        CANCELLED: "已取消"
    })[status] || status;
}

function resultReasonLabel(reason) {
    return ({
        NO_PIECES: "棋子不足",
        NO_MOVES: "无子可走",
        TIMEOUT: "超时",
        RESIGN: "认输",
        DRAW_REPETITION: "三次重复",
        DRAW_NO_CAPTURE: "50步无吃子",
        DRAW_AGREED: "协议和棋",
        ABORTED: "首步超时"
    })[reason] || reason;
}

async function enterMyGame(gameId) {
    if (!client.identity || client.entryBusy) return;
    if (client.active) leaveGame();
    client.epoch++;
    client.game = null;
    const ctx = context();
    entryBusy(true);
    try {
        const game = await api(`/api/v1/games/${gameId}/session`, { headers: authHeader(client.identity.clientToken) });
        if (!matches(ctx)) return;
        client.session = { gameId: game.id, token: client.identity.clientToken,
            side: mySide(game), playerName: client.identity.nickname, bearer: true };
        client.roomCode = null;
        saveSession();
        enterGame(game, "已通过身份打开对局");
    } catch (error) {
        if (matches(ctx)) showToast(readableError(error));
    } finally {
        if (matches(ctx)) entryBusy(false);
    }
}

function mySide(game) {
    return game.whitePlayer === client.identity.nickname ? "WHITE" : "BLACK";
}

async function lookupRoomCode() {
    const code = elements.roomCodeInput.value.trim();
    if (!/^\d{6}$/.test(code)) {
        showToast("请输入 6 位房间码");
        return;
    }
    try {
        const room = await api(`/api/v1/rooms/${code}`);
        if (client.identity) {
            // Identity-aware path: join with the saved player identity (Bearer), not the legacy
            // anonymous form, so the game is recorded against this player and shows up in this
            // browser's own "我的对局" list too - the room-code flow is the primary way a second
            // player joins, so this is the common case, not a fallback.
            await joinRoomAsIdentity(room.gameId);
            return;
        }
        elements.joinGameId.value = room.gameId;
        showToast("已找到对局，请输入姓名后点击加入对局");
        const joinNameField = elements.joinForm.elements.blackPlayer;
        if (joinNameField) joinNameField.focus();
    } catch (error) {
        showToast(readableError(error));
    }
}

async function joinRoomAsIdentity(gameId) {
    if (client.entryBusy) return;
    if (client.active) leaveGame();
    client.epoch++;
    client.game = null;
    const ctx = context();
    entryBusy(true);
    try {
        const response = await api(`/api/v1/games/${gameId}/join`, {
            method: "POST", headers: { ...authHeader(client.identity.clientToken), "Idempotency-Key": randomToken() }
        });
        if (!matches(ctx)) return;
        // Bearer joins never return a per-game credential (the identity's own bearer token
        // already authenticates every later request for this game) - see createGame()'s matching
        // comment for the create-side equivalent.
        client.session = { gameId: response.game.id, token: client.identity.clientToken,
            side: "BLACK", playerName: client.identity.nickname, bearer: true };
        client.roomCode = null;
        saveSession();
        enterGame(response.game, "已通过身份加入对局");
        void renderMyGames();
    } catch (error) {
        if (matches(ctx)) showToast(readableError(error));
    } finally {
        if (matches(ctx)) entryBusy(false);
    }
}

async function copyRoomCode() {
    if (!client.roomCode) return;
    try {
        await navigator.clipboard.writeText(client.roomCode);
        showToast("房间码已复制，发给朋友即可加入");
    } catch {
        showToast(`房间码：${client.roomCode}`);
    }
}

function randomToken() {
    const bytes = new Uint8Array(32);
    window.crypto.getRandomValues(bytes);
    return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function createIdempotencyKey() { return randomToken(); }

function readableError(error) {
    if (error?.code === "GAME_BUSY") return "该对局正在处理其他请求，请稍后重试";
    return error?.code ? `${error.code}: ${error.message}` : error?.message || "请求失败，请确认后端正在运行";
}

let toastTimer;
function showToast(message) {
    clearTimeout(toastTimer);
    elements.toast.textContent = message;
    elements.toast.classList.add("is-visible");
    toastTimer = setTimeout(() => elements.toast.classList.remove("is-visible"), 4200);
}

window.addEventListener("beforeunload", disconnectRealtime);
