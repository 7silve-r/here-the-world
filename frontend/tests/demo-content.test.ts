import { describe, expect, it } from "vitest";
import { existsSync } from "node:fs";
import { upgradeDemoContent } from "../src/mocks/demo-content";
import {
  DEMO_CONTENT_VERSION,
  demoUser,
  otherUser,
  seedWorks,
} from "../src/mocks/seed";

function legacy() {
  const works = seedWorks();
  works[0].title = "山海之间，自有回响";
  works[0].body = "旧的演示正文";
  return {
    demoContentVersion: 0,
    accounts: [
      {
        user: {
          ...demoUser,
          username: "lin_studio",
          displayName: "林间来信",
          bio: "把经过的地方，变成可以停留的片刻。\n摄影 / 日常观察 / 偶尔写诗",
          avatarUrl: null,
        },
      },
      {
        user: {
          ...otherUser,
          username: "yu_wandering",
          displayName: "余野",
          bio: "收集风的形状，和一些没有答案的问题。",
          avatarUrl: null,
        },
      },
    ],
    works,
  };
}

describe("atmospheric demo data upgrade", () => {
  it("replaces bundled presentation while preserving versions, interactions, custom works and deletions", () => {
    const db = {
      ...legacy(),
      comments: [{ id: "comment_keep", body: "保留评论" }],
      bookmarks: { work_01: ["user_lin"] },
    };
    const work = db.works[0];
    work.version = 7;
    work.stats.likeCount = 90;
    const stats = { ...work.stats },
      created = work.createdAt;
    const custom = {
      ...structuredClone(work),
      id: "work_custom",
      title: "我自己的作品",
      body: "不替换正文",
    };
    const customBefore = structuredClone(custom);
    db.works = db.works.filter((w) => w.id !== "work_02");
    db.works.push(custom);
    expect(upgradeDemoContent(db)).toBe(true);
    expect(db.demoContentVersion).toBe(DEMO_CONTENT_VERSION);
    expect(work.title).toBe(seedWorks()[0].title);
    expect(work.version).toBe(7);
    expect(work.stats).toEqual(stats);
    expect(work.createdAt).toBe(created);
    expect(db.works.find((w) => w.id === "work_custom")).toEqual(customBefore);
    expect(db.works.some((w) => w.id === "work_02")).toBe(false);
    expect(db.comments).toEqual([{ id: "comment_keep", body: "保留评论" }]);
    expect(db.bookmarks).toEqual({ work_01: ["user_lin"] });
    expect(db.accounts[0].user.displayName).toBe(demoUser.displayName);
    expect(db.accounts[0].user.avatarUrl).toBe(demoUser.avatarUrl);
  });
  it("preserves customized profile fields and does not repeat a completed upgrade", () => {
    const db = legacy();
    db.accounts[0].user.displayName = "我的昵称";
    db.accounts[0].user.bio = "我的介绍";
    Object.assign(db.accounts[0].user, {
      avatarUrl: "https://example.com/my-avatar.png",
    });
    upgradeDemoContent(db);
    expect(db.accounts[0].user.displayName).toBe("我的昵称");
    expect(db.accounts[0].user.bio).toBe("我的介绍");
    expect(db.accounts[0].user.avatarUrl).toBe(
      "https://example.com/my-avatar.png",
    );
    db.works[0].title = "升级之后保留的修改";
    expect(upgradeDemoContent(db)).toBe(false);
    expect(db.works[0].title).toBe("升级之后保留的修改");
  });
  it("does not clear or downgrade data with a newer content version", () => {
    const db = { ...legacy(), demoContentVersion: DEMO_CONTENT_VERSION + 1 };
    const original = structuredClone(db);
    expect(upgradeDemoContent(db)).toBe(false);
    expect(db).toEqual(original);
  });
  it("keeps all five work types and every demo media reference available locally", () => {
    const works = seedWorks();
    expect(works).toHaveLength(32);
    expect(new Set(works.map((w) => w.kind))).toEqual(
      new Set(["IMAGE", "TEXT", "AUDIO", "VIDEO", "MUSIC_LINK"]),
    );
    expect(new Set(works.map((w) => w.title)).size).toBe(32);
    for (const work of works) {
      const urls = [
        work.cover?.url,
        ...work.assets.map((a) => a.url),
        work.author.avatarUrl,
      ].filter(Boolean) as string[];
      for (const url of urls)
        expect(existsSync(`public${new URL(url).pathname}`)).toBe(true);
    }
  });
});
