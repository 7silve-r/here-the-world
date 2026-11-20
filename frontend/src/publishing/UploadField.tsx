import { useEffect, useRef, useState } from "react";
import { FileUp, X } from "lucide-react";
import { api, ApiError } from "../api/client";
import type { Asset, Capabilities, UploadTicket } from "../api/generated";
import { Button, Notice } from "../shared/ui";
import { pollAsset, putBinary } from "./upload";
type Row = {
  id: string;
  key?: string;
  file: File;
  preview: string;
  progress: number;
  asset?: Asset;
  error?: unknown;
  ticket?: UploadTicket;
  controller: AbortController;
};
export default function UploadField({
  purpose,
  mediaType,
  capabilities,
  onChange,
  onBusyChange,
  initial = [],
  multiple = false,
  label,
}: {
  purpose: Asset["purpose"];
  mediaType: Asset["mediaType"];
  capabilities: Capabilities;
  onChange: (assets: Asset[]) => void;
  onBusyChange?: (busy: boolean) => void;
  initial?: Asset[];
  multiple?: boolean;
  label: string;
}) {
  const [rows, setRows] = useState<Row[]>([]),
    [existing, setExisting] = useState(initial),
    [error, setError] = useState<unknown>();
  const rowRef = useRef(rows),
    callback = useRef(onChange);
  callback.current = onChange;
  const busyCallback = useRef(onBusyChange);
  busyCallback.current = onBusyChange;
  const rule = capabilities.uploadRules.find(
      (r) => r.purpose === purpose && r.mediaType === mediaType,
    ),
    existingRef = useRef(existing);
  existingRef.current = existing;
  const emit = () => {
    callback.current([
      ...existingRef.current,
      ...rowRef.current.flatMap((r) => (r.asset ? [r.asset] : [])),
    ]);
    busyCallback.current?.(
      rowRef.current.some((row) => !row.asset && !row.error),
    );
  };
  function update(id: string, patch: Partial<Row>) {
    rowRef.current = rowRef.current.map((r) =>
      r.id === id ? { ...r, ...patch } : r,
    );
    setRows(rowRef.current);
    emit();
  }
  useEffect(
    () => () => {
      busyCallback.current?.(false);
      rowRef.current.forEach((r) => {
        r.controller.abort();
        URL.revokeObjectURL(r.preview);
        if (r.ticket && !r.asset)
          void api("cancelUpload", undefined, {
            params: { uploadId: r.ticket.uploadId },
          }).catch(() => {});
      });
    },
    [],
  );
  async function start(row: Row) {
    const controller = new AbortController();
    const replace =
      row.asset?.status === "FAILED" ||
      (row.error instanceof ApiError && row.error.code === "UPLOAD_EXPIRED");
    const key = replace ? crypto.randomUUID() : row.key || crypto.randomUUID();
    update(row.id, {
      key,
      controller,
      error: undefined,
      ...(replace ? { asset: undefined, ticket: undefined, progress: 0 } : {}),
    });
    try {
      const input = {
        purpose,
        fileName: row.file.name,
        contentType: row.file.type,
        sizeBytes: row.file.size,
      };
      const ticket =
        (!replace && row.ticket) ||
        (await api("createUpload", input, { key, signal: controller.signal }));
      update(row.id, { ticket });
      if (!row.asset || replace)
        await putBinary(ticket, row.file, controller.signal, (progress) =>
          update(row.id, { progress }),
        );
      const asset =
        (!replace && row.asset) ||
        (await api(
          "completeUpload",
          { sizeBytes: row.file.size },
          { params: { uploadId: ticket.uploadId }, signal: controller.signal },
        ));
      update(row.id, { asset });
      const final = await pollAsset(asset, controller.signal, (asset) =>
        update(row.id, { asset }),
      );
      if (final.status === "FAILED")
        update(row.id, {
          error: new Error(
            `文件处理失败 · ${final.failureCode || "请更换文件"}`,
          ),
        });
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") return;
      update(row.id, { error: e });
    }
  }
  function remove(row: Row) {
    row.controller.abort();
    URL.revokeObjectURL(row.preview);
    if (row.ticket && !row.asset)
      void api("cancelUpload", undefined, {
        params: { uploadId: row.ticket.uploadId },
      }).catch(setError);
    rowRef.current = rowRef.current.filter((r) => r.id !== row.id);
    setRows(rowRef.current);
    emit();
  }
  return (
    <div className="upload-section">
      <span className="field-title">{label}</span>
      <Notice error={error} />
      <label className="upload-drop">
        <FileUp size={23} strokeWidth={1.3} />
        <strong>
          选择
          {mediaType === "IMAGE"
            ? "图片"
            : mediaType === "VIDEO"
              ? "视频"
              : "音频"}
          文件
        </strong>
        <span>
          {rule?.allowedMimeTypes.map((m) => m.split("/")[1]).join(" / ")} ·
          单个不超过 {Math.round((rule?.maxBytes || 0) / 1048576)} MB
        </span>
        <input
          aria-label={label}
          type="file"
          accept={rule?.allowedMimeTypes.join(",")}
          multiple={multiple}
          onChange={(e) => {
            const files = Array.from(e.target.files || []);
            e.target.value = "";
            setError(undefined);
            if (
              files.length + rowRef.current.length + existing.length >
              (multiple ? capabilities.maxImagesPerWork : 1)
            ) {
              setError(
                new Error(
                  multiple
                    ? `最多选择 ${capabilities.maxImagesPerWork} 张图片`
                    : "请先移除现有文件",
                ),
              );
              return;
            }
            for (const file of files) {
              if (!rule?.allowedMimeTypes.includes(file.type)) {
                setError(new Error("不支持这个文件格式"));
                continue;
              }
              if (file.size <= 0 || file.size > rule.maxBytes) {
                setError(new Error("文件为空或超过大小限制"));
                continue;
              }
              const row: Row = {
                id: crypto.randomUUID(),
                file,
                preview: URL.createObjectURL(file),
                progress: 0,
                controller: new AbortController(),
              };
              rowRef.current = [...rowRef.current, row];
              setRows(rowRef.current);
              void start(row);
            }
          }}
        />
      </label>
      <div className="upload-rows">
        {existing.map((a) => (
          <div className="upload-row" key={a.id}>
            <span>
              {a.mediaType} · {a.status}
            </span>
            <button
              type="button"
              className="icon-button"
              aria-label="移除已有媒体"
              onClick={() => {
                existingRef.current = existing.filter((v) => v.id !== a.id);
                setExisting(existingRef.current);
                emit();
              }}
            >
              <X size={15} />
            </button>
          </div>
        ))}
        {rows.map((row) => (
          <div className="upload-row" key={row.id}>
            {mediaType === "IMAGE" ? (
              <img src={row.preview} alt={row.file.name} />
            ) : (
              <FileUp size={24} />
            )}
            <div>
              <strong>{row.file.name}</strong>
              <progress max={100} value={row.progress} />
              <small>
                {row.error
                  ? "上传或处理失败"
                  : row.asset?.status === "READY"
                    ? "准备好了"
                    : row.asset?.status === "FAILED"
                      ? "处理失败"
                      : row.asset
                        ? "正在处理，可先保存草稿"
                        : `传输 ${row.progress}%`}
              </small>
              {!!row.error && <Notice error={row.error} />}
              <div className="row-actions">
                {(row.error || row.asset?.status === "FAILED") && (
                  <Button type="button" onClick={() => start(row)}>
                    重新申请并上传
                  </Button>
                )}
                {row.asset?.status === "PROCESSING" && (
                  <Button
                    type="button"
                    onClick={async () => {
                      try {
                        const a = await api("getAsset", undefined, {
                          params: { assetId: row.asset!.id },
                        });
                        update(row.id, { asset: a });
                      } catch (e) {
                        update(row.id, { error: e });
                      }
                    }}
                  >
                    检查状态
                  </Button>
                )}
              </div>
            </div>
            <button
              type="button"
              className="icon-button"
              aria-label={`取消或移除 ${row.file.name}`}
              onClick={() => remove(row)}
            >
              <X size={15} />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
