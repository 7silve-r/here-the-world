import type { PublicUser, WorkDetail } from "../api/generated";
import { DEMO_CONTENT_VERSION, demoUser, otherUser, seedWorks } from "./seed";

type DemoDatabase = {
  demoContentVersion?: number;
  accounts: { user: PublicUser }[];
  works: WorkDetail[];
};

/** Upgrade bundled examples without clearing user-created works or interactions. */
export function upgradeDemoContent(db: DemoDatabase) {
  if ((db.demoContentVersion || 0) >= DEMO_CONTENT_VERSION) return false;
  const defaults = [
    {
      id: demoUser.id,
      previous: {
        username: "lin_studio",
        displayName: "林间来信",
        bio: "把经过的地方，变成可以停留的片刻。\n摄影 / 日常观察 / 偶尔写诗",
      },
      next: demoUser,
    },
    {
      id: otherUser.id,
      previous: {
        username: "yu_wandering",
        displayName: "余野",
        bio: "收集风的形状，和一些没有答案的问题。",
      },
      next: otherUser,
    },
  ];
  for (const { id, previous, next } of defaults) {
    const user = db.accounts.find((a) => a.user.id === id)?.user;
    if (!user) continue;
    for (const field of ["username", "displayName", "bio"] as const)
      if (user[field] === previous[field]) user[field] = next[field];
    const account = db.accounts.find((a) => a.user.id === id);
    if (
      account &&
      !("avatarAssetId" in account && account.avatarAssetId) &&
      !user.avatarUrl
    )
      user.avatarUrl = next.avatarUrl;
  }
  const pending = db.accounts.find((a) => a.user.id === "user_pending")?.user;
  if (pending?.displayName === "等待来信") pending.displayName = "新来的牌友";
  const bundled = new Map(seedWorks().map((w) => [w.id, w]));
  for (const work of db.works) {
    const replacement = bundled.get(work.id);
    if (!replacement) continue;
    // IDs, versions, status, timestamps and all interaction state stay intact.
    for (const field of [
      "title",
      "body",
      "excerpt",
      "cover",
      "assets",
      "coverAsset",
      "musicLink",
    ] as const)
      Object.assign(work, { [field]: replacement[field] });
  }
  db.demoContentVersion = DEMO_CONTENT_VERSION;
  return true;
}
