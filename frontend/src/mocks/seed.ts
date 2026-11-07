import type {
  Asset,
  Capabilities,
  PublicUser,
  WorkDetail,
} from "../api/generated";
import {
  orangeJuiceCharacters,
  type OrangeJuiceCharacter,
} from "../shared/characters";
// Increment after changing bundled content so existing browser demo data upgrades.
export const DEMO_CONTENT_VERSION = 2;
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
  username: "pudding_club",
  displayName: "布丁俱乐部",
  bio: "角色卡收集、桌游截图与牌桌配色。\n喜欢 QP、奶油黄和掷出五点的骰子。",
  avatarUrl: absolute(orangeJuiceCharacters.qp.portrait),
  publishedWorkCount: 0,
  createdAt: "2026-10-01T08:00:00Z",
};
export const otherUser: PublicUser = {
  ...demoUser,
  id: "user_yu",
  username: "star_archive",
  displayName: "星星收藏夹",
  bio: "整理角色头像、卡背图案和轻快的合成音色。\n最近在收集 Poppo 和 Suguri 的卡面。",
  avatarUrl: absolute(orangeJuiceCharacters.poppo.portrait),
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
  dimensions: [number, number] = [1200, 900],
): Asset {
  return {
    id,
    purpose: "WORK_MEDIA",
    mediaType,
    status: "READY",
    mimeType:
      mediaType === "IMAGE"
        ? url.endsWith(".png")
          ? "image/png"
          : url.endsWith(".jpg")
            ? "image/jpeg"
            : "image/svg+xml"
        : mediaType === "VIDEO"
          ? "video/webm"
          : "audio/wav",
    sizeBytes: 1024,
    url: absolute(url),
    thumbnailUrl:
      mediaType === "VIDEO" ? absolute("/media/tabletop-card.svg") : null,
    width: mediaType === "AUDIO" ? null : dimensions[0],
    height: mediaType === "AUDIO" ? null : dimensions[1],
    durationMs: mediaType === "IMAGE" ? null : 6000,
    failureCode: null,
    createdAt: iso(),
  };
}
type DemoArt = OrangeJuiceCharacter | "star" | "die" | "card" | "pudding";
// Edit the demo titles, text and cover selection here. Keep IDs and kinds stable.
export const demoPosts: readonly {
  title: string;
  body: string;
  art: DemoArt;
}[] = [
  {
    title: "QP 的布丁时间",
    body: "把 QP 的粉色外套和布丁的焦糖色放在一起，今天的角色卡就用这组配色。",
    art: "qp",
  },
  {
    title: "Suguri · 天蓝卡面",
    body: "灰发、浅蓝外套和一圈奶油色边框。给 Suguri 留一张清爽的角色卡。",
    art: "suguri",
  },
  {
    title: "今晚的牌桌笔记",
    body: "一只骰子，\n几张喜欢的角色卡，\n再留一个放布丁的位置。\n\n今晚就从这张小桌子开始。",
    art: "card",
  },
  {
    title: "Marc 的飞行日记",
    body: "收集到 Marc 的笑脸卡面。暖橙色外框配上米白底，像刚拆开的桌游卡包。",
    art: "marc",
  },
  {
    title: "星星攒够以后",
    body: "先把星星摆成一排，\n再选一张最喜欢的卡。\n\n不用赶着开始下一局，\n这一刻的牌桌已经很好看。",
    art: "star",
  },
  {
    title: "菜单里的小星星",
    body: "自制的六秒合成音色：柔和的三角音与短促的星星铃声，适合卡牌轻轻落桌的时刻。",
    art: "star",
  },
  {
    title: "Kai 与 Poppo 的同桌合照",
    body: "一张双人角色卡。把 Kai 和 Poppo 放在同一格里，再给旁边留几颗星星。",
    art: "kai",
  },
  {
    title: "角色卡的入场动画",
    body: "QP、Suguri 和 Marc 的卡面在奶油色桌面上轻轻漂浮。这是一段六秒的循环小动画。",
    art: "card",
  },
  {
    title: "骰子停在五点",
    body: "喜欢五点骰子的对称图案。奶黄底、焦糖色点数，再加一点天蓝色阴影。",
    art: "die",
  },
  {
    title: "布丁配奶油黄",
    body: "今天的桌面小物只有一份布丁。把它放在粉色卡牌和薄荷绿卡背之间。",
    art: "pudding",
  },
  {
    title: "一次愉快的重掷",
    body: "这次没有掷到想要的点数。\n没关系，\n把骰子收回来，\n也把桌上的星星重新摆好。",
    art: "die",
  },
  {
    title: "牌桌音乐收藏",
    body: "晚间牌局的音乐收藏，偏爱明亮的电子音色和轻快的节拍。外部音乐通过原平台打开。",
    art: "sora",
  },
  {
    title: "Poppo 的星星口袋",
    body: "橘粉色头发、绿色眼睛和一张得意的笑脸。给 Poppo 的小卡片搭了暖桃色底。",
    art: "poppo",
  },
  {
    title: "Hime 的花饰卡片",
    body: "把 Hime 的花饰和柔粉色描边放在一起，卡背则选了淡淡的紫色。",
    art: "hime",
  },
  {
    title: "收好这一叠卡",
    body: "粉色放在最上面，\n蓝色留在旁边，\n绿色卡背露出一个小角。\n\n光是整理卡片，也能过一个下午。",
    art: "card",
  },
  {
    title: "Sora 的薄荷色周末",
    body: "用 Sora 的头像试了一组薄荷绿和奶黄色。比起整齐排满，更喜欢稍微错开的卡组。",
    art: "sora",
  },
  {
    title: "牌桌上的四种颜色",
    body: "粉、绿、黄、蓝。\n每一种颜色都有自己的座位，\n每一张角色卡都有不同的表情。",
    art: "star",
  },
  {
    title: "掀开卡包的声音",
    body: "一小段自制菜单音色。先响起轻轻的和弦，再落下几颗像拆卡包一样清脆的音符。",
    art: "card",
  },
  {
    title: "Fernet 的绿色卡背",
    body: "给 Fernet 选了一张浅绿色卡背。边框保留纸面的暖色，避免整张卡显得太冷。",
    art: "fernet",
  },
  {
    title: "骰子旁边的小动画",
    body: "三张熟悉的角色卡在桌面上错开入场，给循环牌桌留一点轻快的节奏。",
    art: "die",
  },
  {
    title: "Peat 的收藏格",
    body: "小小的头像也适合放进一张收藏卡。给 Peat 配了偏灰的天蓝色边框。",
    art: "peat",
  },
  {
    title: "Aru 的粉色贴纸",
    body: "Aru 的角色贴纸，配粉白色卡纸和一颗奶黄色星星。准备留给下一页收藏册。",
    art: "aru",
  },
  {
    title: "留一个空位给布丁",
    body: "卡牌已经摆好，\n骰子也已经准备好。\n\n桌角的空位，\n就留给布丁吧。",
    art: "pudding",
  },
  {
    title: "轻快音色备忘录",
    body: "收集适合桌游菜单的轻快音色。音乐入口保留在原平台，卡面则用了一点蓝绿色。",
    art: "suguri",
  },
  {
    title: "Yuki 的紫色小卡",
    body: "给 Yuki 的笑脸配了一张浅紫色卡纸，背后叠一张偏蓝的卡背。",
    art: "yuki",
  },
  {
    title: "Tomomo 的奶油色框",
    body: "把 Tomomo 收进今天的角色册。米白边框、纸面阴影，再加一点温暖的橙色。",
    art: "tomomo",
  },
  {
    title: "一局结束后的桌面",
    body: "角色卡还没收起，\n骰子停在桌边。\n\n把最后几颗星星放好，\n给下一局留一点期待。",
    art: "star",
  },
  {
    title: "星星与卡背练习",
    body: "同一颗星星可以配不同的卡背。试着让粉色、薄荷绿和天蓝色在桌面上交替出现。",
    art: "card",
  },
  {
    title: "今天选哪位角色",
    body: "QP 的粉色、Suguri 的天蓝色，\n还有 Poppo 的暖桃色。\n\n先选喜欢的表情，\n再开始今天的一局。",
    art: "qp",
  },
  {
    title: "奶油色桌面的和弦",
    body: "六秒原创合成和弦和星星铃声，没有人声。把音量放轻一点，听卡牌入场时的节奏。",
    art: "pudding",
  },
  {
    title: "十二位角色的小收藏",
    body: "从 QP 和 Suguri 开始，慢慢把 Marc、Kai、Poppo、Sora、Hime 等角色收进同一本册子。",
    art: "qp",
  },
  {
    title: "再开一局的入场卡",
    body: "三张角色卡在奶油色桌面上上下轻摆。骰子准备好了，下一次入场从这里开始。",
    art: "marc",
  },
];

function demoImage(art: DemoArt) {
  if (art in orangeJuiceCharacters) {
    const src = orangeJuiceCharacters[art as OrangeJuiceCharacter].src;
    const size = src.endsWith(".jpg") ? 150 : 300;
    return { src, size };
  }
  return { src: `/media/tabletop-${art}.svg`, size: 240 };
}
export function seedWorks(): WorkDetail[] {
  return demoPosts.map(({ title, body, art }, i) => {
    const image = demoImage(art);
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
            seedAsset(`asset_seed_${i}`, "IMAGE", image.src, [
              image.size,
              image.size,
            ]),
          ]
        : kind === "AUDIO"
          ? [
              seedAsset(
                `asset_seed_${i}`,
                "AUDIO",
                "/media/demo/tabletop-chimes.wav",
              ),
            ]
          : kind === "VIDEO"
            ? [
                seedAsset(
                  `asset_seed_${i}`,
                  "VIDEO",
                  "/media/demo/party-loop.webm",
                  [640, 360],
                ),
              ]
            : [];
    const cover =
      kind === "TEXT" || kind === "AUDIO" || kind === "MUSIC_LINK"
        ? null
        : {
            url: absolute(image.src),
            width: image.size,
            height: image.size,
            blurHash: null,
          };
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
              title: "牌桌音乐收藏",
              artist: null,
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
