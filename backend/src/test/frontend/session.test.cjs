const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const {webcrypto} = require('node:crypto');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
const A = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', B = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const session = id => ({gameId:id, token:'secret', side:'WHITE', playerName:'Alice'});
const game = (id=A,version=1) => ({id,version,whitePlayer:'Alice',blackPlayer:'Bob',phase:'PLACING',status:'IN_PROGRESS',
    legalPlacements:['A1'],legalMoves:{},removablePieces:[],state:{board:{},currentPlayer:'WHITE',whitePiecesToPlace:9,blackPiecesToPlace:9}});
const response = body => ({ok:true,status:200,text:async()=>JSON.stringify(body)});
function harness() {
    const nodes = new Map(), storage = new Map(), timers = new Map(); let timerId=0;
    const node = () => ({hidden:false,disabled:false,dataset:{},elements:[],style:{setProperty(){}},
        classList:{add(){},remove(){},toggle(){}},setAttribute(){},addEventListener(){},appendChild(){},append(){},replaceChildren(){},querySelectorAll(){return [];}});
    const document = {getElementById(id){if(!nodes.has(id))nodes.set(id,node());return nodes.get(id);},createElement:node};
    class FakeSocket {
        constructor(url){this.url=url;this.readyState=0;this.events={};this.sent=[];}
        addEventListener(type,fn){(this.events[type] ||= []).push(fn);}
        send(payload){this.sent.push(JSON.parse(payload));}
        emit(type,event={}){if(type==='open')this.readyState=1;if(type==='close')this.readyState=3;for(const fn of this.events[type]||[])fn(event);}
        close(){this.emit('close',{code:1000});}
    }
    const identityStore = new Map();
    const ctx=vm.createContext({document,sessionStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},
        localStorage:{getItem:k=>identityStore.get(k)||null,setItem:(k,v)=>identityStore.set(k,v),removeItem:k=>identityStore.delete(k)},
        window:{location:{search:'',pathname:'/',protocol:'http:',host:'localhost',href:'http://localhost/'},crypto:webcrypto,confirm:()=>true,addEventListener(){}},
        history:{replaceState(){}},URL,URLSearchParams,Uint8Array,AbortController,btoa,console,WebSocket:FakeSocket,
        setTimeout:(fn,ms)=>{const id=++timerId;timers.set(id,{fn,ms});return id;},clearTimeout:id=>timers.delete(id),
        setInterval:(fn,ms)=>{const id=++timerId;timers.set(id,{fn,ms,interval:true});return id;},clearInterval:id=>timers.delete(id),
        fetch:async()=>response(game())});
    vm.runInContext(source,ctx);
    const run=code=>vm.runInContext(code,ctx);
    const save=data=>storage.set('morris-live-session-v1',JSON.stringify(data));
    const activate=(id=A)=>{ctx.savedSession=session(id);ctx.savedGame=game(id);run('client.session=savedSession; client.active=true; client.game=savedGame; saveSession();');};
    const timer=async ms=>{const entry=[...timers].find(([,v])=>v.ms===ms);assert.ok(entry, 'expected timer '+ms);timers.delete(entry[0]);await entry[1].fn();};
    return {ctx,run,save,activate,timer,storage,nodes};
}
function confirm(h) {
    h.run('client.socket.emit("open"); client.socket.emit("message",{data:JSON.stringify({type:"SUBSCRIBED",gameId:client.session.gameId})});');
}
test('temporary restoration failure preserves credentials; explicit invalid and missing are distinguished without silent deletion',async()=>{
    for(const code of [null,'INVALID_PLAYER_TOKEN','GAME_NOT_FOUND']) {
        const h=harness();h.save({session:session(A)});
        h.ctx.fetch=async()=>{if(!code)throw new TypeError('offline');return {ok:false,status:code==='GAME_NOT_FOUND'?404:403,text:async()=>JSON.stringify({code})};};
        await h.run('restoreSession()');
        assert.equal(JSON.parse([...h.storage.values()][0]).session.token,'secret');
        assert.equal(h.nodes.get('setupPanel').hidden,false);
    }
});
test('return to entry keeps identity and can restore',async()=>{
    const h=harness();h.activate();h.run('leaveGame()');
    assert.equal(JSON.parse([...h.storage.values()][0]).session.gameId,A);
    await h.run('restoreSession()');
    assert.equal(h.run('client.active'),true);assert.equal(h.run('client.game.id'),A);
});
test('old refresh response and wrong-game push cannot overwrite new game; lower versions ignored',async()=>{
    const h=harness();h.activate();let resolve;h.ctx.fetch=()=>new Promise(r=>resolve=r);
    const pending=h.run('refreshGame()');h.run('leaveGame()');h.activate(B);
    resolve(response(game(A,99)));await pending;
    h.ctx.badGame=game(A,100);h.run('applyGame(badGame,"WEBSOCKET")');
    assert.equal(h.run('client.game.id'),B);assert.equal(h.run('client.game.version'),1);
    h.ctx.badGame=game(B,0);h.run('applyGame(badGame,"REST")');assert.equal(h.run('client.game.version'),1);
});
test('uncertain action retries identical key payload version and prevents duplicate clicks',async()=>{
    const h=harness();h.activate();const requests=[];
    h.ctx.fetch=async(p,r)=>{requests.push({...r});if(requests.length===1)throw new TypeError('response lost');return response(game(A,2));};
    const pending=h.run('performAction("PLACE",null,"A1")');
    await new Promise(setImmediate);
    await h.run('performAction("PLACE",null,"D1")');
    await h.timer(500);await pending;
    assert.equal(requests.length,2);assert.equal(requests[0].headers['Idempotency-Key'],requests[1].headers['Idempotency-Key']);
    assert.equal(requests[0].body,requests[1].body);assert.equal(JSON.parse(requests[1].body).expectedVersion,1);
    assert.equal(h.run('client.pendingAction'),null);
});
test('retry is bounded and unresolved operation survives reload',async()=>{
    const h=harness();h.activate();let count=0;h.ctx.fetch=async()=>{count++;throw new TypeError('offline');};
    const pending=h.run('performAction("PLACE",null,"A1")');await new Promise(setImmediate);
    await h.timer(500);await new Promise(setImmediate);await h.timer(1000);await pending;
    assert.equal(count,3);assert.ok(JSON.parse([...h.storage.values()][0]).pendingAction.key);
});
test('reconnect snapshot and subsequent polling recover both offline moves and subscribe/read gap',async()=>{
    const h=harness();h.activate();let version=2;h.ctx.fetch=async()=>response(game(A,version));
    h.run('connectRealtime();');confirm(h);await new Promise(setImmediate);
    assert.equal(h.run('client.game.version'),2);
    version=3;await h.timer(2000);assert.equal(h.run('client.game.version'),3);
    version=4;h.run('client.socket.emit("close",{code:1006});');await h.timer(2200);confirm(h);await new Promise(setImmediate);
    assert.equal(h.run('client.game.version'),4);
});
test('lost join response keeps proof and recovery reuses it',async()=>{
    const h=harness();h.save({pendingJoin:{gameId:A,blackPlayer:'Bob',joinToken:'b'.repeat(43)}});const bodies=[];
    h.ctx.fetch=async(p,r)=>{bodies.push(r.body);if(bodies.length===1)throw new TypeError('lost');return response({game:game(),credential:{playerName:'Bob'}});};
    await h.run('restoreSession()');assert.ok(JSON.parse([...h.storage.values()][0]).pendingJoin);
    await h.run('restoreSession()');assert.equal(bodies[0],bodies[1]);assert.equal(h.run('client.session.token'),'b'.repeat(43));
});
test('late create response cannot enter a newer session',async()=>{
    const h=harness();let resolve;h.ctx.fetch=()=>new Promise(r=>resolve=r);
    h.ctx.event={preventDefault(){},currentTarget:{elements:{whitePlayer:{value:'Alice'}}}};
    const pending=h.run('createGame(event)');h.run('leaveGame()');h.activate(B);
    resolve(response({game:game(A),whiteCredential:{token:'old',playerName:'Alice'}}));await pending;
    assert.equal(h.run('client.session.gameId'),B);
});
test('late action response cannot clear or overwrite a newer game',async()=>{
    const h=harness();h.activate();let resolve;h.ctx.fetch=()=>new Promise(r=>resolve=r);
    const pending=h.run('performAction("PLACE",null,"A1")');h.run('leaveGame()');h.activate(B);
    h.run('client.pendingAction={gameId:client.session.gameId,key:"new-pending"};');
    resolve(response(game(A,20)));await pending;
    assert.equal(h.run('client.game.id'),B);assert.equal(h.run('client.pendingAction.key'),'new-pending');
});
test('GAME_BUSY retries unchanged; VERSION_CONFLICT refreshes without resubmitting a new action',async()=>{
    const h=harness();h.activate();const requests=[];
    h.ctx.fetch=async(p,r)=>{
        if(r.method==='GET')return response(game(A,5));
        requests.push(r);
        return {ok:false,status:requests.length===1?503:409,text:async()=>JSON.stringify({code:requests.length===1?'GAME_BUSY':'VERSION_CONFLICT'})};
    };
    const pending=h.run('performAction("PLACE",null,"A1")');await new Promise(setImmediate);await h.timer(500);await pending;
    assert.equal(requests.length,2);assert.equal(requests[0].body,requests[1].body);
    assert.equal(requests[0].headers['Idempotency-Key'],requests[1].headers['Idempotency-Key']);
    assert.equal(h.run('client.pendingAction'),null);assert.equal(h.run('client.game.version'),5);
});
test('late close from a replaced socket cannot change current connection status',()=>{
    const h=harness();h.activate();
    h.run('connectRealtime(); const oldSocket=client.socket; connectRealtime(); setConnection("live","current"); oldSocket.emit("close",{code:1006});');
    assert.equal(h.nodes.get('connectionText').textContent,'current');
});
test('open sends proof without URL credentials; snapshot is requested only after matching subscription confirmation',async()=>{
    const h=harness();h.activate();let reads=0;h.ctx.fetch=async()=>{reads++;return response(game(A,2));};
    h.run('connectRealtime(); client.socket.emit("open");');
    assert.equal(h.run('client.subscribed'),false);assert.equal(reads,0);
    assert.equal(h.run('client.socket.url'),'ws://localhost/ws');
    assert.deepEqual(JSON.parse(h.run('JSON.stringify(client.socket.sent[0])')),{type:'SUBSCRIBE',gameId:A,token:'secret'});
    h.ctx.wrongId=B;h.run('client.socket.emit("message",{data:JSON.stringify({type:"SUBSCRIBED",gameId:wrongId})});');
    assert.equal(h.run('client.subscribed'),false);assert.equal(reads,0);
    h.run('client.socket.emit("message",{data:JSON.stringify({type:"SUBSCRIBED",gameId:client.session.gameId})});');
    await new Promise(setImmediate);assert.equal(reads,1);assert.equal(h.run('client.game.version'),2);
});
test('unconfirmed and stale socket state frames cannot change current board',()=>{
    const h=harness();h.activate();h.ctx.push=JSON.stringify({type:'GAME_STATE',game:game(A,8)});
    h.run('connectRealtime(); const previousSocket=client.socket; client.socket.emit("message",{data:push});');
    assert.equal(h.run('client.game.version'),1);
    h.run('connectRealtime(); previousSocket.emit("message",{data:push});');
    assert.equal(h.run('client.game.version'),1);
});
test('authentication failure stops automatic reconnect but keeps identity and periodic snapshots',async()=>{
    const h=harness();h.activate();
    h.run('connectRealtime(); const rejectedSocket=client.socket; client.socket.emit("message",{data:JSON.stringify({type:"ERROR",code:"INVALID_PLAYER_TOKEN"})});');
    assert.equal(h.run('client.subscribed'),false);assert.ok(JSON.parse([...h.storage.values()][0]).session.token);
    h.ctx.fetch=async()=>response(game(A,4));await h.timer(2000);
    assert.equal(h.run('client.socket === rejectedSocket'),true);assert.equal(h.run('client.game.version'),4);
    await assert.rejects(()=>h.timer(2200));
});
test('subscription timeout reconnects, but leaving cancels reconnect and polling',async()=>{
    const h=harness();h.activate();h.run('connectRealtime(); const timedOutSocket=client.socket;');
    await h.timer(8000);await h.timer(2200);
    assert.equal(h.run('client.socket !== timedOutSocket'),true);
    h.run('client.socket.emit("close",{code:1006}); leaveGame();');
    await assert.rejects(()=>h.timer(2200));await assert.rejects(()=>h.timer(2000));
});

test('recovery card appears only for saved identity and hides after explicit clear',()=>{
    const h=harness();assert.equal(h.nodes.get('recoveryCard').hidden,true);
    h.activate();h.run('leaveGame()');assert.equal(h.nodes.get('recoveryCard').hidden,false);
    h.run('clearSession()');assert.equal(h.nodes.get('recoveryCard').hidden,true);
});

test('invitation includes game ID but never credentials or unrelated query parameters',async()=>{
    const h=harness();h.activate();let copied;
    h.ctx.navigator={clipboard:{writeText:async text=>{copied=text;}}};
    h.ctx.window.location.href='https://example.com/?token=private&other=1#private';
    await h.run('copyGameId()');
    assert.equal(copied,`https://example.com/?game=${A}`);
    assert.equal(h.nodes.get('inviteLink').value,copied);
});

test('snapshot success without subscription does not claim realtime connectivity',async()=>{
    const h=harness();h.activate();await h.run('refreshGame()');
    assert.equal(h.nodes.get('connectionPill').dataset.state,'snapshot');
    assert.equal(h.nodes.get('realtimeBadge').textContent,'快照同步');
    h.run('client.subscribed=true');await h.run('refreshGame()');
    assert.equal(h.nodes.get('connectionPill').dataset.state,'live');
});

test('board counts are distinct from unplaced pieces; pending notice takes priority over opponent turn',()=>{
    const h=harness();h.activate();
    h.run('client.game.state.board={A1:"WHITE",D1:"BLACK",G1:"WHITE"}; client.game.state.whitePiecesToPlace=7; client.game.state.currentPlayer="BLACK"; client.pendingAction={key:"pending"}; renderGame();');
    assert.equal(h.nodes.get('whiteOnBoard').textContent,'2');assert.equal(h.nodes.get('whitePieces').textContent,'7');
    assert.equal(h.nodes.get('operationNotice').hidden,false);
    assert.match(h.nodes.get('actionPrompt').textContent,/未确认/);
});

test('latest change marker uses adjacent versions and ignores stale snapshots',()=>{
    const h=harness();h.activate();h.ctx.next=game(A,2);h.ctx.next.state.board={A1:'WHITE'};
    h.run('applyGame(next,"REST")');assert.equal(h.run('client.lastChanged.join()'),'A1');
    h.ctx.next=game(A,1);h.run('applyGame(next,"REST")');assert.equal(h.run('client.lastChanged.join()'),'A1');
    h.ctx.next=game(A,5);h.run('applyGame(next,"REST")');assert.equal(h.run('client.lastChanged.length'),0);
});

test('saveIdentity persists to localStorage and loadIdentity reads it back',()=>{
    const h=harness();const store={};
    h.ctx.localStorage={getItem:k=>(k in store?store[k]:null),setItem:(k,v)=>{store[k]=v;},removeItem:k=>{delete store[k];}};
    h.ctx.identity={playerId:'p1',nickname:'Han',clientToken:'x'.repeat(43)};
    h.run('saveIdentity(identity)');
    // Compared via the raw persisted JSON (not the vm-realm object returned by loadIdentity itself,
    // whose plain-object prototype lives in a different vm context/realm than this test's own
    // literals, which would make assert.deepStrictEqual's prototype check fail spuriously).
    assert.deepStrictEqual(JSON.parse(store['morris.player.v1']),{playerId:'p1',nickname:'Han',clientToken:'x'.repeat(43)});
    const loaded=h.run('loadIdentity()');
    assert.equal(loaded.playerId,'p1');assert.equal(loaded.nickname,'Han');assert.equal(loaded.clientToken,'x'.repeat(43));
});

test('loadIdentity returns null and does not throw when localStorage access throws',()=>{
    const h=harness();
    h.ctx.localStorage={getItem:()=>{throw new Error('SecurityError');}};
    assert.strictEqual(h.run('loadIdentity()'),null);
});

test('formatClock renders mm:ss and never negative',()=>{
    const h=harness();
    assert.strictEqual(h.run('formatClock(65_000)'),'01:05');
    assert.strictEqual(h.run('formatClock(0)'),'00:00');
    assert.strictEqual(h.run('formatClock(-500)'),'00:00');
});

test('computeClockDisplay corrects for client/server clock skew when counting down to turnDeadlineAt',()=>{
    const h=harness();
    // Client-local clock runs 2s ahead of the server: skewMs = localNow - serverNow = 2000.
    // correctedNow = localNow - skewMs must land back on the server's own timeline, so with a
    // deadline 10s after that corrected instant, ~10s (not ~8s) should remain.
    h.ctx.clock={whiteMs:60_000,blackMs:60_000,running:true,
        serverNow:'2026-01-01T00:00:00Z',turnDeadlineAt:'2026-01-01T00:00:10Z'};
    h.ctx.state={whitePiecesToPlace:5,blackPiecesToPlace:5};
    const localNowMs=Date.parse('2026-01-01T00:00:02Z');
    const skewMs=2000;
    const display=h.run(`computeClockDisplay(clock, state, "WHITE", ${localNowMs}, ${skewMs})`);
    assert.ok(display.white.ms<=10_000&&display.white.ms>9_000,`expected ~10000ms remaining, got ${display.white.ms}`);
    assert.strictEqual(display.white.graceMs,null);
    assert.strictEqual(display.black.ms,60_000);
});

test('computeClockDisplay keeps a first move\'s stored time static and reports a separate grace countdown',()=>{
    const h=harness();
    h.ctx.clock={whiteMs:300_000,blackMs:300_000,running:true,
        serverNow:'2026-01-01T00:00:00Z',turnDeadlineAt:'2026-01-01T00:00:30Z'};
    h.ctx.state={whitePiecesToPlace:9,blackPiecesToPlace:9};
    const localNowMs=Date.parse('2026-01-01T00:00:12Z');
    const display=h.run(`computeClockDisplay(clock, state, "WHITE", ${localNowMs}, 0)`);
    assert.strictEqual(display.white.ms,300_000);
    assert.strictEqual(display.white.graceMs,18_000);
    assert.strictEqual(display.black.ms,300_000);
    assert.strictEqual(display.black.graceMs,null);
});

test('computeClockDisplay counts a non-first move down from turnDeadlineAt and never below zero',()=>{
    const h=harness();
    h.ctx.clock={whiteMs:60_000,blackMs:45_000,running:true,
        serverNow:'2026-01-01T00:00:00Z',turnDeadlineAt:'2026-01-01T00:00:05Z'};
    h.ctx.state={whitePiecesToPlace:3,blackPiecesToPlace:0};
    const midMove=h.run(`computeClockDisplay(clock, state, "BLACK", ${Date.parse('2026-01-01T00:00:02Z')}, 0)`);
    assert.strictEqual(midMove.black.ms,3_000);
    assert.strictEqual(midMove.black.graceMs,null);
    assert.strictEqual(midMove.white.ms,60_000);
    const afterDeadline=h.run(`computeClockDisplay(clock, state, "BLACK", ${Date.parse('2026-01-01T00:00:09Z')}, 0)`);
    assert.strictEqual(afterDeadline.black.ms,0);
});

test('computeClockDisplay leaves both sides static when the clock is not running',()=>{
    const h=harness();
    h.ctx.clock={whiteMs:12_000,blackMs:34_000,running:false,
        serverNow:'2026-01-01T00:00:00Z',turnDeadlineAt:null};
    h.ctx.state={whitePiecesToPlace:0,blackPiecesToPlace:0};
    const display=h.run('computeClockDisplay(clock, state, "WHITE", Date.now(), 0)');
    assert.strictEqual(display.white.ms,12_000);
    assert.strictEqual(display.black.ms,34_000);
    assert.strictEqual(display.white.graceMs,null);
    assert.strictEqual(display.black.graceMs,null);
});

test('gameStatusLabel covers the M2 terminal statuses',()=>{
    const h=harness();
    assert.strictEqual(h.run('gameStatusLabel("DRAWN")'),'和棋');
    assert.strictEqual(h.run('gameStatusLabel("ABORTED")'),'已放弃（首步超时）');
    assert.strictEqual(h.run('gameStatusLabel("CANCELLED")'),'已取消');
});

test('resultReasonLabel maps every backend reason to Chinese copy',()=>{
    const h=harness();
    assert.strictEqual(h.run('resultReasonLabel("TIMEOUT")'),'超时');
    assert.strictEqual(h.run('resultReasonLabel("RESIGN")'),'认输');
    assert.strictEqual(h.run('resultReasonLabel("DRAW_REPETITION")'),'三次重复');
    assert.strictEqual(h.run('resultReasonLabel("DRAW_NO_CAPTURE")'),'50步无吃子');
    assert.strictEqual(h.run('resultReasonLabel("DRAW_AGREED")'),'协议和棋');
    assert.strictEqual(h.run('resultReasonLabel("NO_PIECES")'),'棋子不足');
    assert.strictEqual(h.run('resultReasonLabel("NO_MOVES")'),'无子可走');
});

test('opponent presence badge shows the required offline copy and resets when leaving the game',()=>{
    const h=harness();h.activate();
    h.run('client.session.side="WHITE"; updatePresence("ONLINE","OFFLINE");');
    assert.strictEqual(h.nodes.get('opponentPresence').textContent,'对手已离线，棋钟仍在计时');
    h.run('updatePresence("ONLINE","ONLINE");');
    assert.strictEqual(h.nodes.get('opponentPresence').textContent,'对手在线');
    h.run('leaveGame();');
    assert.strictEqual(h.nodes.get('opponentPresence').textContent,'—');
});

test('PRESENCE websocket messages update the badge even before SUBSCRIBED arrives',()=>{
    const h=harness();h.activate();
    h.run('client.session.side="BLACK"; connectRealtime(); client.socket.emit("open");');
    h.run('client.socket.emit("message",{data:JSON.stringify({type:"PRESENCE",white:"OFFLINE",black:"ONLINE"})});');
    assert.strictEqual(h.run('client.subscribed'),false);
    assert.strictEqual(h.nodes.get('opponentPresence').textContent,'对手已离线，棋钟仍在计时');
});

test('game actions are hidden for legacy anonymous sessions without a bearer token',()=>{
    const h=harness();h.activate();
    h.run('client.session.bearer=false; renderGame();');
    assert.strictEqual(h.nodes.get('gameActions').hidden,true);
});

test('WAITING_FOR_PLAYER shows only Cancel for the creating (white) identity session',()=>{
    const h=harness();h.activate();
    h.run('client.session.bearer=true; client.session.side="WHITE"; client.game.status="WAITING_FOR_PLAYER"; renderGame();');
    assert.strictEqual(h.nodes.get('cancelButton').hidden,false);
    assert.strictEqual(h.nodes.get('resignButton').hidden,true);
});

test('IN_PROGRESS shows accept/decline when the opponent offered a draw, and the waiting text when I did',()=>{
    const h=harness();h.activate();
    h.run('client.session.bearer=true; client.session.side="WHITE"; client.game.status="IN_PROGRESS"; client.game.drawOfferedBy="BLACK"; renderGame();');
    assert.strictEqual(h.nodes.get('drawAcceptButton').hidden,false);
    assert.strictEqual(h.nodes.get('drawDeclineButton').hidden,false);
    assert.strictEqual(h.nodes.get('drawOfferButton').hidden,true);
    h.run('client.game.drawOfferedBy="WHITE"; renderGame();');
    assert.strictEqual(h.nodes.get('drawStatusText').hidden,false);
    assert.strictEqual(h.nodes.get('drawAcceptButton').hidden,true);
});

test('a finished game offers rematch, and shows accept/decline once the opponent proposed one',()=>{
    const h=harness();h.activate();
    h.run('client.session.bearer=true; client.session.side="WHITE"; client.game.status="WHITE_WON"; renderGame();');
    assert.strictEqual(h.nodes.get('rematchButton').hidden,false);
    h.run('client.game.rematchOfferedBy="BLACK"; renderGame();');
    assert.strictEqual(h.nodes.get('rematchAcceptButton').hidden,false);
    assert.strictEqual(h.nodes.get('rematchButton').hidden,true);
});

test('a rematchGameId appearing on the current game navigates into the new game',async()=>{
    const h=harness();h.activate();
    h.run('client.identity={playerId:"p1",nickname:"Alice",clientToken:"x".repeat(43)}; client.session.bearer=true;');
    const rematch=game('cccccccc-cccc-cccc-cccc-cccccccccccc');rematch.whitePlayerId='p2';rematch.blackPlayerId='p1';
    h.ctx.fetch=async()=>response(rematch);
    h.ctx.next=game(A,2);h.ctx.next.status='WHITE_WON';h.ctx.next.whitePlayerId='p1';h.ctx.next.blackPlayerId='p2';
    h.ctx.next.rematchGameId='cccccccc-cccc-cccc-cccc-cccccccccccc';
    h.run('applyGame(next,"REST")');
    await new Promise(setImmediate);await new Promise(setImmediate);await new Promise(setImmediate);
    assert.strictEqual(h.run('client.session.gameId'),'cccccccc-cccc-cccc-cccc-cccccccccccc');
    assert.strictEqual(h.run('client.session.side'),'BLACK'); // colours swap in a rematch
});

// Live bug: enterMyGame/joinRoomAsIdentity released entryBusy only if matches(ctx), but ctx was
// taken before the session switched to the entered game, so a successful entry left entryBusy
// stuck at true and every later entry - including the automatic jump into a rematch - silently
// returned. Accepting a second rematch then appeared to do nothing.
test('entering a game from my games releases entryBusy so later entries still work',async()=>{
    const h=harness();
    h.run('client.identity={playerId:"black-id",nickname:"Sam",clientToken:"x".repeat(43)};');
    h.ctx.fetch=async()=>response(identityGame(B,'white-id','black-id'));
    await h.run('enterMyGame(B_ID)'.replace('B_ID',JSON.stringify(B)));
    assert.strictEqual(h.run('client.entryBusy'),false);
});

test('joining by room code as an identity releases entryBusy',async()=>{
    const h=harness();
    h.run('client.identity={playerId:"black-id",nickname:"Sam",clientToken:"x".repeat(43)};');
    h.ctx.fetch=async()=>response({game:identityGame(B,'white-id','black-id')});
    await h.run('joinRoomAsIdentity(B_ID)'.replace('B_ID',JSON.stringify(B)));
    assert.strictEqual(h.run('client.session.gameId'),B);
    assert.strictEqual(h.run('client.entryBusy'),false);
});

test('a player who joined by room code can still be taken into an accepted rematch',async()=>{
    const h=harness();
    const C='cccccccc-cccc-cccc-cccc-cccccccccccc';
    h.run('client.identity={playerId:"black-id",nickname:"Sam",clientToken:"x".repeat(43)};');
    h.ctx.fetch=async()=>response({game:identityGame(A,'white-id','black-id')});
    await h.run('joinRoomAsIdentity(A_ID)'.replace('A_ID',JSON.stringify(A)));
    const rematch=identityGame(C,'black-id','white-id');
    h.ctx.fetch=async()=>response(rematch);
    h.ctx.next=identityGame(A,'white-id','black-id');h.ctx.next.version=9;h.ctx.next.status='DRAWN';h.ctx.next.rematchGameId=C;
    h.run('applyGame(next,"REST")');
    await new Promise(setImmediate);await new Promise(setImmediate);await new Promise(setImmediate);
    assert.strictEqual(h.run('client.session.gameId'),C);
    assert.strictEqual(h.run('client.session.side'),'WHITE');
});

test('the offering player is told when the opponent declines the rematch',()=>{
    const h=harness();h.activate();
    h.run('client.identity={playerId:"white-id",nickname:"Sam",clientToken:"x".repeat(43)}; client.session.bearer=true;');
    h.ctx.g1=identityGame(A,'white-id','black-id');h.ctx.g1.version=5;h.ctx.g1.status='DRAWN';h.ctx.g1.rematchOfferedBy='WHITE';
    h.run('applyGame(g1,"WEBSOCKET")');
    h.ctx.g2=identityGame(A,'white-id','black-id');h.ctx.g2.version=6;h.ctx.g2.status='DRAWN';h.ctx.g2.rematchOfferedBy=null;
    h.run('applyGame(g2,"WEBSOCKET")');
    assert.strictEqual(h.nodes.get('toast').textContent,'对方拒绝了再来一局');
    assert.strictEqual(h.nodes.get('rematchButton').hidden,false);
});

test('the declining player gets a confirmation instead of a silent button swap',()=>{
    const h=harness();h.activate();
    h.run('client.identity={playerId:"white-id",nickname:"Sam",clientToken:"x".repeat(43)}; client.session.bearer=true;');
    h.ctx.g1=identityGame(A,'white-id','black-id');h.ctx.g1.version=5;h.ctx.g1.status='DRAWN';h.ctx.g1.rematchOfferedBy='BLACK';
    h.run('applyGame(g1,"WEBSOCKET")');
    h.ctx.g2=identityGame(A,'white-id','black-id');h.ctx.g2.version=6;h.ctx.g2.status='DRAWN';h.ctx.g2.rematchOfferedBy=null;
    h.run('applyGame(g2,"REST")');
    assert.strictEqual(h.nodes.get('toast').textContent,'已拒绝再来一局');
});

test('my games also lists finished games whose rematch is still open, flagging a pending offer',async()=>{
    const h=harness();
    h.run('client.identity={playerId:"p1",nickname:"Sam",clientToken:"x".repeat(43)};');
    const urls=[];
    h.ctx.fetch=async url=>{urls.push(url);
        if(url.includes('status=FINISHED'))return response({games:[
            {gameId:'f1',status:'DRAWN',opponentNickname:'Bo',rematchOpen:true,opponentOfferedRematch:true},
            {gameId:'f2',status:'WHITE_WON',opponentNickname:'Cy',rematchOpen:true,opponentOfferedRematch:false},
            {gameId:'f3',status:'BLACK_WON',opponentNickname:'Di',rematchOpen:false,opponentOfferedRematch:false}]});
        return response({games:[{gameId:'a1',status:'IN_PROGRESS',opponentNickname:'Al'}]});};
    const texts=[];
    h.ctx.document.createElement=()=>{const n={children:[],textContent:'',addEventListener(){},appendChild(c){n.children.push(c);},classList:{add(){}}};return n;};
    h.nodes.get('my-games-list').appendChild=item=>texts.push(item.children.map(c=>c.textContent).join(''));
    await h.run('renderMyGames()');
    assert.ok(urls.some(u=>u.includes('status=ACTIVE')) && urls.some(u=>u.includes('status=FINISHED')));
    assert.strictEqual(texts.length,3); // the expired f3 is not listed
    assert.match(texts[0],/Al/);
    assert.ok(texts.some(t=>/Bo/.test(t) && /邀请/.test(t)));
    assert.ok(texts.some(t=>/Cy/.test(t) && /再来一局/.test(t) && !/邀请/.test(t)));
});

test('leaving a game refreshes my games so the game just left can be found again',async()=>{
    const h=harness();h.activate();
    h.run('client.identity={playerId:"p1",nickname:"Sam",clientToken:"x".repeat(43)}; client.session.bearer=true; client.game.status="DRAWN";');
    const urls=[];
    h.ctx.fetch=async url=>{urls.push(url);return response({games:[]});};
    h.run('leaveGame()');
    await new Promise(setImmediate);
    assert.ok(urls.some(u=>u.includes('/players/me/games')));
});

test('joining your own game explains how to join as the opponent in Chinese',()=>{
    const h=harness();
    h.ctx.err={code:'CANNOT_JOIN_OWN_GAME',message:'Use a different browser or device to join as the other player'};
    assert.match(h.run('readableError(err)'),/无痕窗口/);
    h.ctx.err={code:'GAME_ALREADY_FULL',message:'The game already has two players'};
    assert.match(h.run('readableError(err)'),/已满/);
});

// Final-review C1: sides come from player ids, never nicknames.
const identityGame=(id,whiteId,blackId)=>{const g=game(id);g.whitePlayer='Sam';g.blackPlayer='Sam';g.whitePlayerId=whiteId;g.blackPlayerId=blackId;return g;};
test('mySide uses player ids: two identities with the same nickname, viewer is black',()=>{
    const h=harness();
    h.run('client.identity={playerId:"black-id",nickname:"Sam",clientToken:"x".repeat(43)};');
    h.ctx.g=identityGame(A,'white-id','black-id');
    assert.strictEqual(h.run('mySide(g)'),'BLACK');
    h.run('client.identity.playerId="white-id"');
    assert.strictEqual(h.run('mySide(g)'),'WHITE');
});

test('mySide falls back to the stored session side when the game has no player ids (legacy anonymous)',()=>{
    const h=harness();h.activate();
    h.run('client.session.side="BLACK"; client.identity=null;');
    h.ctx.g=game(A);
    assert.strictEqual(h.run('mySide(g)'),'BLACK');
});

test('opening a game from my games as the same-nickname black player enters as BLACK',async()=>{
    const h=harness();
    h.run('client.identity={playerId:"black-id",nickname:"Sam",clientToken:"x".repeat(43)};');
    h.ctx.fetch=async()=>response(identityGame(B,'white-id','black-id'));
    await h.run('enterMyGame(B_ID)'.replace('B_ID',JSON.stringify(B)));
    assert.strictEqual(h.run('client.session.side'),'BLACK');
    assert.strictEqual(JSON.parse(h.storage.get('morris-live-session-v1')).session.side,'BLACK');
});

test('an identity session saved with the wrong side is corrected from the player ids on the next snapshot',()=>{
    const h=harness();h.activate();
    h.run('client.identity={playerId:"black-id",nickname:"Sam",clientToken:"x".repeat(43)}; client.session.bearer=true; client.session.side="WHITE";');
    h.ctx.next=identityGame(A,'white-id','black-id');h.ctx.next.version=2;
    h.run('applyGame(next,"REST")');
    assert.strictEqual(h.run('client.session.side'),'BLACK');
    assert.strictEqual(JSON.parse(h.storage.get('morris-live-session-v1')).session.side,'BLACK');
});

// Final-review I1: only IN_PROGRESS is playable; finished games show winner plus reason.
for (const [status,winner,reason,expected] of [
    ['BLACK_WON','BLACK','TIMEOUT','黑方胜 · 超时'],
    ['WHITE_WON','WHITE','RESIGN','白方胜 · 认输'],
    ['DRAWN',null,'DRAW_REPETITION','和棋 · 三次重复'],
    ['ABORTED',null,'ABORTED','已放弃 · 首步超时']]) {
    test(`a ${status}/${reason} game with no engine winner is not interactive and shows "${expected}"`,async()=>{
        const h=harness();h.activate();
        h.run(`client.game.status=${JSON.stringify(status)}; client.game.result={winner:${JSON.stringify(winner)},reason:${JSON.stringify(reason)}};
            client.game.state.winner=null; client.game.state.currentPlayer=client.session.side; renderGame();`);
        assert.strictEqual(h.run('client.game.state.winner'),null);
        const buttons=[];h.run('elements.board').querySelectorAll=()=>buttons;
        for(const p of ['A1','D1'])buttons.push({dataset:{position:p},classList:{toggle(){}},setAttribute(){},disabled:false});
        h.run('renderBoard()');
        assert.ok(buttons.every(b=>b.disabled),'every board position must be disabled');
        assert.strictEqual(h.nodes.get('actionPrompt').textContent,`${expected}，对局结束`);
        assert.strictEqual(h.nodes.get('currentPlayer').textContent,expected);
        assert.strictEqual(h.nodes.get('resultReason').hidden,false);
        assert.strictEqual(h.nodes.get('resultReason').textContent,expected);
        let requests=0;h.ctx.fetch=async()=>{requests++;return response(game());};
        await h.run('handlePositionClick("A1")');
        assert.strictEqual(requests,0);assert.strictEqual(h.run('client.pendingAction'),null);
    });
}

test('the turn highlight follows status, not state.winner',()=>{
    const h=harness();h.activate();
    const toggles={};h.nodes.set('whiteCard',{classList:{toggle:(c,on)=>{toggles.white=on;}}});
    h.run('client.game.status="IN_PROGRESS"; client.game.state.currentPlayer="WHITE"; renderGame();');
    assert.strictEqual(toggles.white,true);
    h.run('client.game.status="BLACK_WON"; client.game.result={winner:"BLACK",reason:"TIMEOUT"}; renderGame();');
    assert.strictEqual(toggles.white,false);
    assert.doesNotMatch(h.nodes.get('whiteRole').textContent,/当前回合/);
});

// Final-review I3: a finished identity game must not block starting a new one.
const identitySession=id=>({gameId:id,token:'x'.repeat(43),side:'WHITE',playerName:'Alice',bearer:true});
const createEvent=()=>({preventDefault(){},currentTarget:{elements:{whitePlayer:{value:'Alice'},timeControl:{value:'5+3'}}}});
test('after leaving a finished game, creating a new game is not blocked',async()=>{
    const h=harness();
    h.ctx.savedSession=identitySession(A);h.ctx.savedGame=game(A);h.ctx.savedGame.status='BLACK_WON';
    h.run('client.identity={playerId:"p1",nickname:"Alice",clientToken:"x".repeat(43)}; client.session=savedSession; client.active=true; client.game=savedGame; saveSession();');
    h.run('leaveGame()');
    assert.strictEqual(h.storage.has('morris-live-session-v1'),false,'the finished game is released');
    assert.strictEqual(h.nodes.get('recoveryCard').hidden,true);
    const requests=[];
    h.ctx.fetch=async(p,r)=>{requests.push({p,r});return {ok:true,status:201,text:async()=>JSON.stringify({game:game(B),roomCode:'123456'})};};
    h.ctx.event=createEvent();
    await h.run('createGame(event)');
    const creates=requests.filter(x=>x.r.method==='POST');
    assert.strictEqual(creates.length,1);assert.strictEqual(creates[0].p,'/api/v1/games');
    assert.strictEqual(h.run('client.session.gameId'),B);
});

test('a saved identity game with nothing in flight is replaced by a new game instead of blocking',async()=>{
    const h=harness();h.save({session:identitySession(A)});
    h.run('client.identity={playerId:"p1",nickname:"Alice",clientToken:"x".repeat(43)};');
    h.ctx.fetch=async()=>({ok:true,status:201,text:async()=>JSON.stringify({game:game(B),roomCode:'123456'})});
    h.ctx.event=createEvent();
    await h.run('createGame(event)');
    assert.strictEqual(JSON.parse(h.storage.get('morris-live-session-v1')).session.gameId,B);
});

test('a pending unconfirmed action still blocks starting a new game, even after leaving a finished game',async()=>{
    for (const leaveFirst of [false,true]) {
        const h=harness();
        h.ctx.savedSession=identitySession(A);h.ctx.savedGame=game(A);h.ctx.savedGame.status='BLACK_WON';
        h.run('client.identity={playerId:"p1",nickname:"Alice",clientToken:"x".repeat(43)}; client.session=savedSession; client.active=true; client.game=savedGame; client.pendingAction={gameId:savedSession.gameId,key:"k",body:{}}; saveSession();');
        if (leaveFirst) h.run('leaveGame()');
        assert.ok(JSON.parse(h.storage.get('morris-live-session-v1')).pendingAction,'the unconfirmed action is kept');
        let requests=0;h.ctx.fetch=async()=>{requests++;return response(game(B));};
        h.ctx.event=createEvent();
        await h.run('createGame(event)');
        assert.strictEqual(requests,0);
        assert.match(h.nodes.get('toast').textContent,/未确认/);
    }
});

test('a legacy anonymous saved seat still blocks starting a new game',async()=>{
    const h=harness();h.save({session:session(A)});
    let requests=0;h.ctx.fetch=async()=>{requests++;return response(game(B));};
    h.ctx.event=createEvent();
    await h.run('createGame(event)');
    assert.strictEqual(requests,0);
});
