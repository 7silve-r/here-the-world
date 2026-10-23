import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  ArrowUpRight,
  Check,
  LoaderCircle,
  Save,
} from "lucide-react";
import type { Asset, MusicLink, WorkDetail, WorkInput } from "../api/generated";
import { api, ApiError } from "../api/client";
import { queryClient } from "../api/cache";
import {
  Button,
  Loading,
  Notice,
  Page,
  kindIcons,
  kindNames,
} from "../shared/ui";
import { Field, Textarea, useIntentKey } from "../shared/forms";
import UploadField from "./UploadField";
import { abortableDelay, waitUntilVisible } from "../shared/hooks";
const blank: WorkInput = {
  kind: "IMAGE",
  title: "",
  body: "",
  mediaAssetIds: [],
  coverAssetId: null,
  musicLinkId: null,
};
export default function Publish() {
  const { workId } = useParams(),
    navigate = useNavigate();
  const [input, setInput] = useState<WorkInput>(blank),
    [current, setCurrent] = useState<WorkDetail | null>(null),
    [error, setError] = useState<unknown>(),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [mediaUploading, setMediaUploading] = useState(false),
    [coverUploading, setCoverUploading] = useState(false),
    [assets, setAssets] = useState<Asset[]>([]),
    [cover, setCover] = useState<Asset[]>([]),
    [music, setMusic] = useState<MusicLink | null>(null),
    [share, setShare] = useState(""),
    [loaded, setLoaded] = useState(!workId),
    [conflict, setConflict] = useState<WorkDetail | null>(null),
    [uploaderVersion, setUploaderVersion] = useState(0);
  const intent = useIntentKey(),
    polling = useRef<AbortController | null>(null);
  const capabilities = useQuery({
    queryKey: ["capabilities"],
    queryFn: ({ signal }) => api("getCapabilities", undefined, { signal }),
  });
  function adopt(w: WorkDetail) {
    setCurrent(w);
    setInput({
      kind: w.kind,
      title: w.title,
      body: w.body,
      mediaAssetIds: w.assets.map((a) => a.id),
      coverAssetId: w.coverAsset?.id || null,
      musicLinkId: w.musicLink?.id || null,
    });
    setAssets(w.assets);
    setCover(w.coverAsset ? [w.coverAsset] : []);
    setMusic(w.musicLink);
    setUploaderVersion((v) => v + 1);
  }
  useEffect(() => {
    if (!workId) {
      setLoaded(true);
      return;
    }
    const c = new AbortController();
    setLoaded(false);
    api("getWork", undefined, { params: { workId }, signal: c.signal })
      .then((w) => {
        adopt(w);
        setLoaded(true);
      })
      .catch((e) => {
        if (!c.signal.aborted) {
          setError(e);
          setLoaded(true);
        }
      });
    return () => c.abort();
  }, [workId]);
  useEffect(() => {
    if (current?.status !== "PROCESSING") return;
    const c = new AbortController();
    polling.current = c;
    const start = Date.now();
    (async () => {
      let delay = 1000;
      try {
        while (Date.now() - start < 120000) {
          await waitUntilVisible(c.signal);
          await abortableDelay(delay, c.signal);
          const w = await api("getWork", undefined, {
            params: { workId: current.id },
            signal: c.signal,
          });
          setCurrent(w);
          if (w.status !== "PROCESSING") break;
          delay = Math.min(5000, delay * 1.4);
        }
        if (!c.signal.aborted && Date.now() - start >= 120000)
          setMessage("仍在处理中，可以稍后在“我的作品”查看。");
      } catch (e) {
        if (!c.signal.aborted) setError(e);
      }
    })();
    return () => c.abort();
  }, [current?.id, current?.status]);
  const locked =
      !!current && ["PROCESSING", "PUBLISHED"].includes(current.status),
    cap = capabilities.data;
  const assembled = (): WorkInput => ({
    ...input,
    mediaAssetIds: assets.map((a) => a.id),
    coverAssetId: cover[0]?.id || null,
    musicLinkId: input.kind === "MUSIC_LINK" ? music?.id || null : null,
  });
  async function save(publish: boolean) {
    if (mediaUploading || coverUploading) {
      setError(new Error("请先等待文件传输完成，媒体处理中可以保存草稿。"));
      return;
    }
    let activeWork = current;
    setBusy(true);
    setError(undefined);
    setMessage("");
    try {
      const body = assembled();
      if (
        !body.title.trim() ||
        Array.from(body.title).length > (cap?.maxTitleLength || 120)
      )
        throw new ApiError(422, "VALIDATION_FAILED", "请检查作品标题", "", [
          {
            field: "title",
            reason: !body.title.trim() ? "REQUIRED" : "TOO_LONG",
            message: `标题需为 1–${cap?.maxTitleLength || 120} 个字符`,
          },
        ]);
      if (Array.from(body.body).length > (cap?.maxBodyLength || 20000))
        throw new ApiError(422, "VALIDATION_FAILED", "正文超过长度限制", "", [
          {
            field: "body",
            reason: "TOO_LONG",
            message: `正文最多 ${cap?.maxBodyLength || 20000} 个字符`,
          },
        ]);
      let w: WorkDetail;
      if (current)
        w = await api("updateWork", body, {
          params: { workId: current.id },
          version: current.version,
        });
      else w = await api("createWork", body, { key: intent.key(body) });
      setCurrent(w);
      activeWork = w;
      intent.reset();
      if (!workId) {
        navigate(`/publish/${encodeURIComponent(w.id)}`, { replace: true });
      }
      if (publish) {
        w = await api("publishWork", undefined, {
          params: { workId: w.id },
          version: w.version,
        });
        setCurrent(w);
        setMessage(
          w.status === "PUBLISHED"
            ? "作品已发布。"
            : "已提交，作品正在处理中。",
        );
      } else setMessage("草稿已保存。");
      queryClient.invalidateQueries({ queryKey: ["list"] });
    } catch (e) {
      setError(e);
      if (e instanceof ApiError && e.status === 412 && activeWork) {
        const latest = await api("getWork", undefined, {
          params: { workId: activeWork.id },
        });
        setConflict(latest);
      }
    } finally {
      setBusy(false);
    }
  }
  if (!loaded || capabilities.isPending) return <Loading />;
  if (workId && (!current || !current.viewer.isOwner))
    return (
      <Page title="无法管理作品">
        <Notice
          error={error}
          message={!error ? "只有作者可以管理自己的作品。" : undefined}
        />
        <Link to="/me/works" className="button">
          回到我的作品
        </Link>
      </Page>
    );
  return (
    <Page title={locked ? "发布状态" : current ? "编辑作品" : "发布作品"}>
      <div className="publish-layout">
        <section className="publish-editor">
          <Notice error={error || capabilities.error} message={message} />
          {conflict && (
            <div className="conflict-panel">
              <p>
                服务器版本 v{conflict.version}：{conflict.title}（
                {conflict.status}）
              </p>
              <p>
                本地输入已保留，请核对后选择重新加载。不会自动覆盖服务器内容。
              </p>
              <Button
                onClick={() => {
                  adopt(conflict);
                  setConflict(null);
                  setError(undefined);
                }}
              >
                使用服务器最新版本
              </Button>
            </div>
          )}
          {locked ? (
            <div className="processing-panel">
              {current.status === "PROCESSING" ? (
                <LoaderCircle className="spin" size={36} />
              ) : (
                <Check size={36} />
              )}
              <h2>
                {current.status === "PROCESSING" ? "作品处理中" : "作品已发布"}
              </h2>
              <p>当前状态：{current.status}</p>
              {current.status === "PUBLISHED" ? (
                <Link
                  className="button primary"
                  to={`/works/${encodeURIComponent(current.id)}`}
                >
                  看看作品 <ArrowUpRight size={16} />
                </Link>
              ) : (
                <>
                  <Button
                    onClick={async () => {
                      try {
                        const w = await api("getWork", undefined, {
                          params: { workId: current.id },
                        });
                        setCurrent(w);
                      } catch (e) {
                        setError(e);
                      }
                    }}
                  >
                    检查处理状态
                  </Button>
                  <Link to="/me/works">稍后再来</Link>
                </>
              )}
            </div>
          ) : (
            cap && (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void save(true);
                }}
              >
                {current?.status === "FAILED" && (
                  <Notice
                    message={`处理失败：${current.failureCode || "请更换失败媒体后保存修正"}`}
                  />
                )}
                <div className="kind-selector" aria-label="作品类型">
                  {Object.entries(kindNames).map(([kind, label]) => {
                    const Icon = kindIcons[kind as WorkInput["kind"]];
                    return (
                      <button
                        key={kind}
                        className={input.kind === kind ? "active" : ""}
                        type="button"
                        disabled={!!current}
                        aria-pressed={input.kind === kind}
                        onClick={() => {
                          setInput({
                            ...blank,
                            kind: kind as WorkInput["kind"],
                            title: input.title,
                            body: input.body,
                          });
                          setAssets([]);
                          setCover([]);
                          setMusic(null);
                          setUploaderVersion((v) => v + 1);
                        }}
                      >
                        <Icon size={20} strokeWidth={1.4} />
                        {label}
                      </button>
                    );
                  })}
                </div>
                <Field
                  label="作品标题"
                  name="title"
                  value={input.title}
                  onChange={(e) =>
                    setInput({ ...input, title: e.target.value })
                  }
                  placeholder="作品标题"
                  required
                  hint={`${Array.from(input.title).length} / ${cap.maxTitleLength}`}
                  error={error}
                />
                <Textarea
                  label={input.kind === "TEXT" ? "正文" : "作品说明"}
                  name="body"
                  value={input.body}
                  onChange={(body) => setInput({ ...input, body })}
                  rows={input.kind === "TEXT" ? 10 : 5}
                  hint={`${Array.from(input.body).length} / ${cap.maxBodyLength}`}
                />
                {["IMAGE", "VIDEO", "AUDIO"].includes(input.kind) && (
                  <UploadField
                    key={`media-${uploaderVersion}-${input.kind}`}
                    label={
                      input.kind === "IMAGE"
                        ? "作品图片（按选择顺序）"
                        : input.kind === "VIDEO"
                          ? "作品视频"
                          : "作品音频"
                    }
                    purpose="WORK_MEDIA"
                    mediaType={input.kind as "IMAGE" | "VIDEO" | "AUDIO"}
                    capabilities={cap}
                    initial={assets}
                    multiple={input.kind === "IMAGE"}
                    onChange={setAssets}
                    onBusyChange={setMediaUploading}
                  />
                )}
                {input.kind === "MUSIC_LINK" && (
                  <div className="resolve-panel">
                    <Field
                      label="音乐分享链接或文案"
                      name="share"
                      value={share}
                      onChange={(e) => setShare(e.target.value)}
                      placeholder="粘贴一条 HTTPS 音乐分享链接"
                    />
                    <Button
                      type="button"
                      busy={busy}
                      onClick={async () => {
                        setBusy(true);
                        try {
                          setMusic(
                            await api("resolveMusicLink", { input: share }),
                          );
                          setError(undefined);
                        } catch (e) {
                          setError(e);
                        } finally {
                          setBusy(false);
                        }
                      }}
                    >
                      解析音乐链接 <ArrowUpRight size={14} />
                    </Button>
                    {music && (
                      <p className="success">
                        <Check size={15} />
                        {music.title || "链接已就绪"} ·{" "}
                        {music.playback.mode === "EXTERNAL"
                          ? "将在原平台打开"
                          : "官方嵌入播放"}
                      </p>
                    )}
                  </div>
                )}
                <details className="cover-options">
                  <summary>自定义封面（可选）</summary>
                  <UploadField
                    key={`cover-${uploaderVersion}`}
                    label="封面图片"
                    purpose="WORK_COVER"
                    mediaType="IMAGE"
                    capabilities={cap}
                    initial={cover}
                    onChange={setCover}
                    onBusyChange={setCoverUploading}
                  />
                </details>
                <div className="publish-buttons">
                  <Button
                    type="button"
                    busy={busy}
                    disabled={!!conflict || mediaUploading || coverUploading}
                    onClick={() => save(false)}
                  >
                    <Save size={16} />
                    保存草稿
                  </Button>
                  <Button
                    type="submit"
                    className="primary"
                    busy={busy}
                    disabled={!!conflict || mediaUploading || coverUploading}
                  >
                    发布作品 <ArrowRight size={16} />
                  </Button>
                </div>
              </form>
            )
          )}
        </section>
      </div>
    </Page>
  );
}
