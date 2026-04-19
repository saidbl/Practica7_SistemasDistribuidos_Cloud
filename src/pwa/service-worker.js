const CACHE_NAME = "sd-monitor-pwa-v1";
const FILES_TO_CACHE = [
  "/",
  "/manifest.json",
  "/styles.css",
  "/app.js",
  "/offline.html"
];

self.addEventListener("install", event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(FILES_TO_CACHE))
  );
  self.skipWaiting();
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(
        keys
          .filter(key => key !== CACHE_NAME)
          .map(key => caches.delete(key))
      )
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", event => {
  if (event.request.method !== "GET") return;

  event.respondWith(
    fetch(event.request)
      .then(networkResponse => {
        const copy = networkResponse.clone();
        caches.open(CACHE_NAME).then(cache => cache.put(event.request, copy));
        return networkResponse;
      })
      .catch(() =>
        caches.match(event.request).then(cached =>
          cached || caches.match("/offline.html")
        )
      )
  );
});