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
    const ctx=vm.createContext({document,sessionStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},
        window:{location:{search:'',pathname:'/',protocol:'http:',host:'localhost',href:'http://localhost/'},crypto:webcrypto,confirm:()=>true,addEventListener(){}},
        history:{replaceState(){}},URL,URLSearchParams,Uint8Array,AbortController,btoa,console,WebSocket:FakeSocket,
        setTimeout:(fn,ms)=>{const id=++timerId;timers.set(id,{fn,ms});return id;},clearTimeout:id=>timers.delete(id),fetch:async()=>response(game())});
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
