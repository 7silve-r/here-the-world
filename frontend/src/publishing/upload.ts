import { api, ApiError, mockMode } from "../api/client";
import type { Asset, UploadTicket } from "../api/generated";
import { abortableDelay, waitUntilVisible } from "../shared/hooks";
export async function putBinary(
  ticket: UploadTicket,
  file: File,
  signal: AbortSignal,
  progress: (v: number) => void,
) {
  if (mockMode) {
    const { mockStoragePut } = await import("../mocks/server");
    try {
      return await mockStoragePut(ticket, file, signal, progress);
    } catch (error) {
      if (
        error &&
        typeof error === "object" &&
        "code" in error &&
        "status" in error
      )
        throw new ApiError(
          Number(error.status),
          String(error.code),
          error instanceof Error ? error.message : "上传失败",
        );
      throw error;
    }
  }
  await new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(ticket.method, ticket.uploadUrl);
    xhr.withCredentials = false;
    Object.entries(ticket.headers).forEach(([k, v]) =>
      xhr.setRequestHeader(k, v),
    );
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) progress(Math.round((e.loaded / e.total) * 100));
    };
    const abort = () => xhr.abort();
    signal.addEventListener("abort", abort, { once: true });
    const cleanup = () => signal.removeEventListener("abort", abort);
    xhr.onload = () => {
      cleanup();
      if (xhr.status >= 200 && xhr.status < 300) resolve();
      else
        reject(
          new ApiError(
            xhr.status,
            [403, 410].includes(xhr.status)
              ? "UPLOAD_EXPIRED"
              : "STORAGE_UPLOAD_FAILED",
            [403, 410].includes(xhr.status)
              ? "上传凭证已过期，请重新申请上传"
              : "文件传输失败，请重试",
          ),
        );
    };
    xhr.onerror = () => {
      cleanup();
      reject(new ApiError(0, "NETWORK_ERROR", "文件传输中断，请重新上传"));
    };
    xhr.onabort = () => {
      cleanup();
      reject(new DOMException("Aborted", "AbortError"));
    };
    if (signal.aborted) {
      cleanup();
      reject(new DOMException("Aborted", "AbortError"));
      return;
    }
    xhr.send(file);
  });
}
export async function pollAsset(
  asset: Asset,
  signal: AbortSignal,
  update: (asset: Asset) => void,
) {
  let current = asset,
    delay = 1000;
  const start = Date.now();
  while (
    ["CREATED", "UPLOADED", "PROCESSING"].includes(current.status) &&
    Date.now() - start < 120000
  ) {
    await waitUntilVisible(signal);
    await abortableDelay(delay, signal);
    current = await api("getAsset", undefined, {
      params: { assetId: asset.id },
      signal,
    });
    update(current);
    delay = Math.min(5000, delay * 1.4);
  }
  return current;
}
