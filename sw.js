// SONORA service worker. Caches only the app shell; never touches IndexedDB or localStorage (your music and data).
// To ship an update: change V (e.g. v1 -> v2) and upload; old shell caches are removed on activate.
const V='sonora-shell-v1',FILES=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png'];
self.addEventListener('install',e=>e.waitUntil(caches.open(V).then(c=>c.addAll(FILES))));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(n=>n.startsWith('sonora-shell-')&&n!==V).map(n=>caches.delete(n)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{const r=e.request;if(r.method!=='GET'||new URL(r.url).origin!==location.origin)return;
 const keep=res=>{if(res&&res.ok){const cp=res.clone();caches.open(V).then(c=>c.put(r,cp))}return res};
 if(r.mode==='navigate'){e.respondWith(fetch(r).then(keep).catch(()=>caches.match('./index.html')));return}
 e.respondWith(caches.match(r).then(h=>h||fetch(r).then(keep).catch(()=>Response.error())))});
