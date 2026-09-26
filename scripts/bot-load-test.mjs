import {randomBytes,randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';

export function validateTarget(value) {
    if(!value)throw new Error('Pass --base-url with an explicit disposable local server');
    const target=new URL(value);
    if(target.protocol!=='http:'||!['localhost','127.0.0.1','[::1]'].includes(target.hostname)||target.username||target.password)
        throw new Error('Load tests only accept a local HTTP server');
    return target;
}
export function percentile(values,p) {
    if(!values.length)throw new Error('No latency samples');
    return [...values].sort((a,b)=>a-b)[Math.ceil(values.length*p)-1];
}
async function main() {
    const args=process.argv.slice(2), option=name=>args[args.indexOf(name)+1];
    const base=validateTarget(args.includes('--base-url')?option('--base-url'):undefined);
    const sampleCount=Number(args.includes('--samples')?option('--samples'):400);
    if(!Number.isInteger(sampleCount)||sampleCount<100)throw new Error('Use at least 100 samples');
    const api=async(path,token,body)=>{
        const start=performance.now();
        const response=await fetch(new URL(path,base),{method:body===undefined?'GET':'POST',
            headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),'Idempotency-Key':randomUUID()},
            body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
        const data=await response.json();
        if(!response.ok)throw new Error(`${path}: ${response.status} ${data.code}`);
        return {data,ms:performance.now()-start};
    };
    const player=async name=>{
        const token=randomBytes(32).toString('base64url');
        const {data}=await api('/api/v1/players',null,{nickname:name,clientToken:token});
        return {token,id:data.playerId};
    };
    const humans=[await player('LoadWhite'),await player('LoadBlack')];
    const owners=[];for(let i=0;i<4;i++)owners.push(await player(`BotLoad${i}`));
    const action=g=>{
        if(g.removablePieces?.length)return {type:'REMOVE',to:g.removablePieces[0],expectedVersion:g.version};
        if(g.legalPlacements?.length)return {type:'PLACE',to:g.legalPlacements[(g.version*7)%g.legalPlacements.length],expectedVersion:g.version};
        const moves=Object.entries(g.legalMoves).flatMap(([from,tos])=>tos.map(to=>({type:'MOVE',from,to,expectedVersion:g.version})));
        if(!moves.length)throw new Error('Active game has no legal action');
        return moves[(g.version*7)%moves.length];
    };
    const tracked=[];
    let humanGame;
    const humanStep=async()=>{
        if(!humanGame||humanGame.status!=='IN_PROGRESS') {
            const {data}=await api('/api/v1/games',humans[0].token,{timeControl:'10+5'});
            tracked.push({id:data.game.id,token:humans[0].token});
            humanGame=(await api(`/api/v1/games/${data.game.id}/join`,humans[1].token,{})).data.game;
        }
        const who=humanGame.state.currentPlayer==='WHITE'?humans[0]:humans[1];
        const reply=await api(`/api/v1/games/${humanGame.id}/actions`,who.token,action(humanGame));
        humanGame=reply.data;return reply.ms;
    };
    let stop=false;const pending=new Map(),botReplies=[];
    let background;
    try {
        for(let i=0;i<80;i++)await humanStep();
        const baseline=[];for(let i=0;i<sampleCount;i++){
            baseline.push(await humanStep());
            await new Promise(r=>setTimeout(r,40));
        }
        const bots=[];
        for(let i=0;i<20;i++){
            const owner=owners[Math.floor(i/5)];
            const {data}=await api('/api/v1/games',owner.token,{opponent:'BOT',difficulty:'HARD',color:'WHITE',timeControl:'10+5'});
            const entry={id:data.game.id,token:owner.token};bots.push(entry);tracked.push(entry);
        }
        background=(async()=>{
            while(!stop){
                for(const bot of bots){
                    if(stop)break;
                    let g=(await api(`/api/v1/games/${bot.id}`)).data;
                    if(g.status!=='IN_PROGRESS')throw new Error('Bot load dropped below 20 active games; rerun on a fresh database');
                    if(g.state.currentPlayer===g.botSide)continue;
                    if(pending.has(bot.id)){botReplies.push(performance.now()-pending.get(bot.id));pending.delete(bot.id);}
                    do {g=(await api(`/api/v1/games/${bot.id}/actions`,bot.token,action(g))).data;}
                    while(g.status==='IN_PROGRESS'&&g.state.currentPlayer!==g.botSide);
                    pending.set(bot.id,performance.now());
                }
                await new Promise(r=>setTimeout(r,100));
            }
        })();
        // Mark rejection observed immediately even while the measurement loop is awaiting HTTP.
        let backgroundError; background.catch(error=>{backgroundError=error;stop=true;});
        await new Promise(r=>setTimeout(r,1500));
        const loaded=[];
        for(let i=0;i<sampleCount;i++){
            if(backgroundError)throw backgroundError;
            loaded.push(await humanStep());
            await new Promise(r=>setTimeout(r,40));
        }
        stop=true;await background;
        const p99=percentile(baseline,.99), loadedP99=percentile(loaded,.99);
        const report={samples:sampleCount,botGames:20,baselineP99Ms:p99,loadedP99Ms:loadedP99,
            increasePercent:(loadedP99/p99-1)*100,passes:loadedP99<=p99*1.2,
            botResponseSamples:botReplies.length,botResponseP99Ms:botReplies.length?percentile(botReplies,.99):null,
            note:'Bot response includes intentional delay, queueing, computation and polling. Queue/search timings below come from scheduler DEBUG logs.'};
        if(args.includes('--server-log')) {
            const log=await readFile(option('--server-log'),'utf8');
            const ids=new Set(bots.map(b=>b.id));
            const samples=[...log.matchAll(/BOT_TIMING game=([\w-]+) queueMs=([\d.]+) searchMs=([\d.]+)/g)].filter(m=>ids.has(m[1]));
            report.queueSamples=samples.length;
            report.queueP99Ms=samples.length?percentile(samples.map(m=>Number(m[2])),.99):null;
            report.searchP99Ms=samples.length?percentile(samples.map(m=>Number(m[3])),.99):null;
        }
        console.log(JSON.stringify(report,null,2));
        if(!report.passes)process.exitCode=2;
    } finally {
        stop=true;if(background)await background.catch(()=>{});
        for(const g of tracked)try{await api(`/api/v1/games/${g.id}/resign`,g.token,{});}catch(error){console.error(`Cleanup: ${g.id}: ${error.message}`);}
    }
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)main().catch(error=>{console.error(error.message);process.exitCode=1;});
