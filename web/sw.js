self.addEventListener('install',e=>self.skipWaiting());
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
self.addEventListener('notificationclick',e=>{
  e.notification.close();
  e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(cs=>{
    for(const c of cs){ c.postMessage({type:'ring',id:e.notification.data&&e.notification.data.id}); return c.focus(); }
    return self.clients.openWindow('./index.html#ring='+(e.notification.data&&e.notification.data.id||''));
  }));
});
