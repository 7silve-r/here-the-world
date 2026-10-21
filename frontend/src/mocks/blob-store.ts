// Uploaded binary lives in IndexedDB, never base64 in work JSON or localStorage.
let dbPromise: Promise<IDBDatabase> | undefined;
function database() {
  return (dbPromise ||= new Promise((resolve, reject) => {
    const request = indexedDB.open("htw-mock-media", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("files");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  }));
}
export async function storeBlob(key: string, blob: Blob) {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction("files", "readwrite");
    tx.objectStore("files").put(blob, key);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}
const urls = new Map<string, string>();
export async function blobUrl(key: string) {
  if (urls.has(key)) return urls.get(key)!;
  const db = await database();
  const blob = await new Promise<Blob | undefined>((resolve, reject) => {
    const r = db.transaction("files").objectStore("files").get(key);
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
  if (!blob) return null;
  const url = URL.createObjectURL(blob);
  urls.set(key, url);
  return url;
}
