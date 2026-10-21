import type {
  Asset,
  Capabilities,
  PublicUser,
  WorkDetail,
} from "../api/generated";
export const iso = () => new Date().toISOString();
export const absolute = (path: string) =>
  new URL(path, globalThis.location?.origin || "http://localhost:5173").href;
export const defaultSettings = {
  theme: "SYSTEM",
  motion: "SYSTEM",
  autoPlayMedia: false,
} as const;
export const demoUser: PublicUser = {
  id: "user_lin",
  username: "lin_studio",
  displayName: "林间来信",
  bio: "把经过的地方，变成可以停留的片刻。\n摄影 / 日常观察 / 偶尔写诗",
  avatarUrl: null,
  publishedWorkCount: 0,
  createdAt: "2026-10-01T08:00:00Z",
};
export const otherUser: PublicUser = {
  ...demoUser,
  id: "user_yu",
  username: "yu_wandering",
  displayName: "余野",
  bio: "收集风的形状，和一些没有答案的问题。",
};
export const capabilities: Capabilities = {
  uploadRules: [
    {
      purpose: "WORK_MEDIA",
      mediaType: "IMAGE",
      allowedMimeTypes: ["image/jpeg", "image/png", "image/webp", "image/gif"],
      maxBytes: 20 * 1048576,
    },
    {
      purpose: "WORK_MEDIA",
      mediaType: "VIDEO",
      allowedMimeTypes: ["video/mp4", "video/webm"],
      maxBytes: 500 * 1048576,
    },
    {
      purpose: "WORK_MEDIA",
      mediaType: "AUDIO",
      allowedMimeTypes: [
        "audio/mpeg",
        "audio/mp4",
        "audio/wav",
        "audio/x-wav",
        "audio/ogg",
        "audio/flac",
      ],
      maxBytes: 100 * 1048576,
    },
    {
      purpose: "WORK_COVER",
      mediaType: "IMAGE",
      allowedMimeTypes: ["image/jpeg", "image/png", "image/webp"],
      maxBytes: 10 * 1048576,
    },
    {
      purpose: "AVATAR",
      mediaType: "IMAGE",
      allowedMimeTypes: ["image/jpeg", "image/png", "image/webp"],
      maxBytes: 5 * 1048576,
    },
  ],
  maxImagesPerWork: 9,
  maxTitleLength: 120,
  maxBodyLength: 20000,
  maxCommentLength: 2000,
  verificationCodeTtlSeconds: 600,
  resendAfterSeconds: 60,
  musicEmbedProviders: [],
};
export function seedAsset(
  id: string,
  mediaType: Asset["mediaType"],
  url: string,
): Asset {
  return {
    id,
    purpose: "WORK_MEDIA",
    mediaType,
    status: "READY",
    mimeType:
      mediaType === "IMAGE"
        ? "image/svg+xml"
        : mediaType === "VIDEO"
          ? "video/webm"
          : "audio/wav",
    sizeBytes: 1024,
    url: absolute(url),
    thumbnailUrl:
      mediaType === "VIDEO" ? absolute("/media/landscape-2.svg") : null,
    width: mediaType === "AUDIO" ? null : 1200,
    height: mediaType === "AUDIO" ? null : 900,
    durationMs: mediaType === "IMAGE" ? null : 6000,
    failureCode: null,
    createdAt: iso(),
  };
}
const titles = [
  "山海之间，自有回响",
  "把日子留给旷野",
  "风经过的时候",
  "城市的褶皱",
  "一封写给远方的信",
  "听见，潮汐",
  "此刻没有目的地",
  "蓝色的慢镜头",
  "树影里的星期天",
  "落日是一场漫长的告别",
  "在寂静处生长",
  "夜航的声音",
  "人间的光，恰好落下",
  "一小片自由",
  "关于夏天的备忘录",
  "再往前，就是海",
  "无用而美好的事",
  "雨后的绿意",
  "沿着河流走",
  "柔软的棱角",
  "世界尽头的书店",
  "山的另一面",
  "慢一点，也没关系",
  "把风装进口袋",
  "空白里的风景",
  "今天的云有点特别",
  "温柔的地平线",
  "向着光的方向",
  "我们在此相遇",
  "远方并不遥远",
  "耳边的森林",
  "最后一班月光",
];
export function seedWorks(): WorkDetail[] {
  return titles.map((title, i) => {
    const kind = (
      [
        "IMAGE",
        "IMAGE",
        "TEXT",
        "IMAGE",
        "TEXT",
        "AUDIO",
        "IMAGE",
        "VIDEO",
        "IMAGE",
        "IMAGE",
        "TEXT",
        "MUSIC_LINK",
      ] as const
    )[i % 12];
    const assets =
      kind === "IMAGE"
        ? [
            seedAsset(
              `asset_seed_${i}`,
              "IMAGE",
              `/media/landscape-${i % 6}.svg`,
            ),
          ]
        : kind === "AUDIO"
          ? [seedAsset(`asset_seed_${i}`, "AUDIO", "/media/field.wav")]
          : kind === "VIDEO"
            ? [seedAsset(`asset_seed_${i}`, "VIDEO", "/media/moving.webm")]
            : [];
    const cover =
      kind === "TEXT" || kind === "AUDIO" || kind === "MUSIC_LINK"
        ? null
        : {
            url: absolute(`/media/landscape-${i % 6}.svg`),
            width: 1200,
            height: 900,
            blurHash: null,
          };
    const body =
      kind === "TEXT"
        ? "我们总在赶路，\n却忘了看看路边的树。\n\n也许生活的意义，\n就是在某个平凡的下午，\n重新发现一束光。"
        : "在路上收集的一些片刻。没有特别的目的，只是想把此刻的感受留下来。愿你也能在这里，遇见属于自己的风景。";
    return {
      id: `work_${String(i + 1).padStart(2, "0")}`,
      kind,
      status: "PUBLISHED",
      version: 1,
      title,
      excerpt: body,
      author: i % 3 === 0 ? demoUser : otherUser,
      cover,
      stats: { likeCount: 12 + i * 7, bookmarkCount: 3 + i, commentCount: 0 },
      viewer: { liked: false, bookmarked: false, isOwner: false },
      createdAt: new Date(Date.UTC(2026, 9, 20, 12, -i)).toISOString(),
      publishedAt: new Date(Date.UTC(2026, 9, 20, 12, -i)).toISOString(),
      body,
      assets,
      coverAsset: null,
      musicLink:
        kind === "MUSIC_LINK"
          ? {
              id: `music_seed_${i}`,
              provider: "NETEASE",
              sourceUrl: "https://music.163.com/",
              title: "夜色与远行",
              artist: "声音札记",
              coverUrl: null,
              metadataStatus: "PARTIAL",
              playback: { mode: "EXTERNAL", embedUrl: null },
            }
          : null,
      updatedAt: iso(),
      failureCode: null,
    };
  });
}
