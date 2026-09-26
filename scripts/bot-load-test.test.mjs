import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateTarget,percentile} from './bot-load-test.mjs';
test('load tool refuses public or missing targets before making requests',()=>{
    for(const url of [undefined,'https://morris.hanzhu-lab.online/','http://example.com','file:///tmp/x'])
        assert.throws(()=>validateTarget(url));
    assert.equal(validateTarget('http://localhost:18080').origin,'http://localhost:18080');
});
test('P99 uses nearest rank and does not hide the slowest tail',()=>{
    assert.equal(percentile(Array.from({length:100},(_,i)=>i+1),.99),99);
    assert.throws(()=>percentile([], .99));
});
