/* Runs the actual browser application and native WebSocket in two isolated
 * JS contexts with minimal DOM adapters, against a running disposable server.
 * This verifies protocol/state behavior, not browser rendering. Node.js 22+. */
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const assert=require('node:assert/strict');
const {webcrypto}=require('node:crypto');
const base=process.env.MORRIS_DEMO_URL;
if(!base)throw new Error('Set MORRIS_DEMO_URL to a server using a disposable test database.');
const root=path.join(__dirname,'../backend/src/main/resources/static');
const app=fs.readFileSync(path.join(root,'app.js'),'utf8');
const clients=[];
function client() {
    const nodes=new Map(),storage=new Map();
    const node=()=>({hidden:false,disabled:false,dataset:{},elements:[],style:{setProperty(){}},classList:{add(){},remove(){},toggle(){}},setAttribute(){},addEventListener(){},appendChild(){},append(){},replaceChildren(){},querySelectorAll(){return [];}});
    let offline=false, loseJoin=false, loseAction=false;const actions=[];
    const context=vm.createContext({console,TextEncoder,TextDecoder,WebSocket,URL,URLSearchParams,Uint8Array,AbortController,btoa,
        setTimeout,clearTimeout,setInterval,clearInterval,
        window:{location:new URL(base),crypto:webcrypto,confirm:()=>true,addEventListener(){}},history:{replaceState(){}},
        document:{getElementById(id){if(!nodes.has(id))nodes.set(id,node());return nodes.get(id);},createElement:node},
        sessionStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},
        fetch:async(url,request)=>{
            if(offline)throw new TypeError('simulated offline');
            if(url.endsWith('/actions'))actions.push({key:request.headers['Idempotency-Key'],body:request.body});
            const result=await fetch(new URL(url,base),request);
            if((loseJoin && url.endsWith('/join')) || (loseAction && url.endsWith('/actions'))) {
                loseJoin=false;loseAction=false;await result.text();throw new TypeError('simulated committed response loss');
            }
            return result;
        }});
    vm.runInContext(app,context);
    const c={run:code=>vm.runInContext(code,context),context,storage,actions,setOffline:v=>offline=v,loseJoin:()=>loseJoin=true,loseAction:()=>loseAction=true};
    clients.push(c);return c;
}
async function until(check,label){const end=Date.now()+12000;while(Date.now()<end){if(check())return;await new Promise(r=>setTimeout(r,50));}throw new Error('Timed out: '+label);}
function event(c,e){c.context.event={preventDefault(){},currentTarget:{elements:e}};}
(async()=>{
    const white=client(),black=client();
    event(white,{whitePlayer:{value:'White demo'}});await white.run('createGame(event)');
    const id=white.run('client.session.gameId');
    await until(()=>white.run('client.subscribed'),'white WebSocket transport');
    black.loseJoin();event(black,{blackPlayer:{value:'Black demo'},gameId:{value:id}});
    await black.run('joinGame(event)');assert.ok(black.run('client.pendingJoin'));
    await black.run('restoreSession()');assert.equal(black.run('client.session.side'),'BLACK');
    await until(()=>white.run('client.game.status')==='IN_PROGRESS','white sees join');
    await until(()=>black.run('client.subscribed'),'black WebSocket transport');
    white.loseAction();await white.run('performAction("PLACE",null,"A1")');
    assert.equal(white.actions.length,2);assert.deepEqual(white.actions[0],white.actions[1]);
    await until(()=>black.run('client.game.state.board.A1')==='WHITE','black receives white move');
    await black.run('performAction("PLACE",null,"B2")');
    await until(()=>white.run('client.game.state.board.B2')==='BLACK','white receives black move');
    assert.ok(white.run('client.logs.some(x=>x.message.includes("实时推送"))'),'white received an actual WebSocket state message');
    assert.ok(black.run('client.logs.some(x=>x.message.includes("实时推送"))'),'black received an actual WebSocket state message');
    black.setOffline(true);black.run('client.socket.close();');
    await white.run('performAction("PLACE",null,"D1")');
    assert.notEqual(black.run('client.game.state.board.D1'),'WHITE');
    black.setOffline(false);
    await until(()=>black.run('client.subscribed'),'automatic reconnect and authentication');
    await until(()=>black.run('client.game.state.board.D1')==='WHITE','reconnect snapshot catches offline move');
    black.run('leaveGame()');await black.run('restoreSession()');assert.equal(black.run('client.game.id'),id);
    // A real authenticated connection still cannot submit game operations or forged state.
    let rejected=false;
    black.context.onRejected=event=>{if(JSON.parse(event.data).type === "ERROR")rejected=true;};
    await until(()=>black.run('client.subscribed'),'restored connection');
    black.run('client.socket.addEventListener("message",onRejected); client.socket.send(JSON.stringify({type:"GAME_STATE",game:{}}));');
    await until(()=>rejected,'forged state rejected');
    const stateAfterRejection=await (await fetch(new URL('/api/v1/games/'+id,base))).json();
    assert.equal(stateAfterRejection.version,white.run('client.game.version'));
    for(const message of [
        {type:'SUBSCRIBE',gameId:id},
        {type:'SUBSCRIBE',gameId:id,token:'not-a-player'},
        {type:'SUBSCRIBE',gameId:'00000000-0000-0000-0000-000000000000',token:white.run('client.session.token')},
        {type:'PLACE',to:'A1'}
    ]) await rejectedConnection(message);
    console.log('PASS: two app clients, real WebSocket delivery, lost join/action responses, same-key retry, offline move recovery, entry restoration, forbidden client state.');
})().catch(error=>{console.error(error);process.exitCode=1;}).finally(()=>{
    for(const c of clients)c.run('leaveGame(); clearTimeout(toastTimer);');
});

async function rejectedConnection(message) {
    const url=new URL('/ws',base);url.protocol=url.protocol==='https:'?'wss:':'ws:';
    const socket=new WebSocket(url);const frames=[];
    await new Promise((resolve,reject)=>{
        const timeout=setTimeout(()=>{socket.close();reject(new Error('Unauthorized socket did not close'));},6000);
        socket.addEventListener('open',()=>socket.send(JSON.stringify(message)));
        socket.addEventListener('message',event=>frames.push(JSON.parse(event.data)));
        socket.addEventListener('error',()=>{clearTimeout(timeout);reject(new Error('Handshake failed unexpectedly'));});
        socket.addEventListener('close',event=>{
            clearTimeout(timeout);
            try {
                assert.equal(event.code,1008);
                assert.equal(frames.length,1);assert.equal(frames[0].type,'ERROR');
                resolve();
            } catch(error){reject(error);}
        });
    });
}
