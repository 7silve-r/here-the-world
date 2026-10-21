# Here the World · 前端

React + TypeScript + Vite。默认使用可变的浏览器 Mock，全部业务组件通过同一个 API 客户端访问 `/api/v1`。没有修改 `backend/` 或 `docs/api/`。

## 启动

需要 Node.js 22.12+（本次使用 Node.js 24.21）。

```powershell
cd frontend
npm ci
npm run dev
```

打开终端打印的本地地址。使用 `localhost` 或 `127.0.0.1` 时请保持同一主机名，浏览器示例数据按 origin 隔离。所有约定路由都可直达；正式静态托管时将非文件、非 `/api` 请求回退到 `index.html`。

```powershell
npm run typecheck
npm test
npm run build
npm run preview
```

## 演示账号与素材

| 项目           | 内容                                      |
| -------------- | ----------------------------------------- |
| 已验证账号     | `world@example.com` / `HelloWorld2026`    |
| 未验证账号     | `pending@example.com` / `HelloWorld2026`  |
| 首次模拟验证码 | `123456`                                  |
| 重发验证码     | `654321`，再次重发切回 `123456`；旧码失效 |
| 验证规则       | 60 秒重发间隔、10 分钟过期、错误尝试限制  |

也可以注册新邮箱。验证仅激活账号，随后需要登录。Mock 不发送邮件；忘记密码页会显示本标签页内的模拟邮件入口。重置页提取 fragment 后立即清理地址，重置成功回到登录。

`public/media/` 内含原创 SVG 风景插画、6 秒合成 WAV 和 6 秒 WebM，五种作品无需远程样例资源。`scripts/generate-media.mjs` 可重建插画和音频；可选传入本机 FFmpeg 路径重建视频。没有移植 Folia 源码、重排表或播放器代码。

Mock 将非敏感示例业务数据放在 localStorage，上传二进制放在 IndexedDB。密码、验证码、access token、重置凭证、上传 URL 不落盘。新注册/修改后的密码只保留在当前页面内存；重新载入后可使用内置账号或在当前标签页申请模拟重置。重置演示邮件同样仅在当前标签页的本次运行中有效。

设置页的“开发演示 · 异常场景”可以安排下一个匹配请求返回 401、404、410、412、429 或媒体处理失败。也可上传文件名包含 `.fail.` 的文件模拟处理失败。失败的作品替换媒体并保存后回到 DRAFT。测试覆盖了媒体尚在 PROCESSING 时保存草稿并提交发布的路径。

## 契约与联调

`src/api/generated.ts` 由仓库原始 OpenAPI 生成，包含 schema 类型、37 个操作的输入/输出及方法/路径。构建会检测类型是否过期：

```powershell
npm run generate:api
npm run check:api
```

Mock 直接读取 `../docs/api/openapi.json`，使用 AJV 校验请求体、成功响应封装和字段。真实模式与 Mock 共享业务客户端，不在页面中返回假成功。

切换后端：

```powershell
Copy-Item .env.example .env.local
```

将 `.env.local` 改成：

```dotenv
VITE_API_MODE=real
API_PROXY_TARGET=http://127.0.0.1:8080
```

重新启动 Vite。浏览器继续使用相对路径 `/api/v1`；`/api` 由开发代理转发。生产部署需提供同源 `/api` 反向代理。真实模式不会启用 Mock 或提供演示登录凭证。

联调建议按契约顺序：capabilities / CSRF → 注册验证登录刷新 → feed / 详情 → 上传与发布 → 互动与个人列表。浏览器 Mock 无法验证真实 HttpOnly Cookie、服务端 Origin 校验、轮换事务、邮件投递、存储签名、文件真实类型或转码；这些必须在真实模式验证。

## 实现位置

| 目录             | 职责                                                                        |
| ---------------- | --------------------------------------------------------------------------- |
| `src/api`        | 生成类型、统一请求、内存鉴权、CSRF、单次共享刷新、跨标签页锁/消息、缓存同步 |
| `src/auth`       | 注册、验证、登录、密码重置、安全 returnTo 与受保护路由                      |
| `src/feed`       | 稳定作品序列、去重队列、slotKey → workId 映射、分页与空间记忆               |
| `src/wall`       | 原创 12×8 几何、稳定命中区域、局部重排动画、相机、裁剪和键盘导航            |
| `src/works`      | 五种详情、原生媒体控件、互动、平层评论与作者删除                            |
| `src/publishing` | 分类型表单、票据、二进制 PUT、进度、取消、资产与作品轮询、版本冲突          |
| `src/profile`    | 公开主页、我的作品状态过滤、收藏和游标分页                                  |
| `src/settings`   | 资料/头像、只读邮箱用户名、服务端与游客展示偏好、修改密码                   |
| `src/mocks`      | 37 个契约操作、可变业务状态、幂等、模拟处理与异常注入                       |

墙面支持四向拖动，悬停短延迟预览、点击进入；触屏首次点按预览，再点打开。方向键移动焦点，Esc 关闭预览/导航。单次最多挂载 396 个槽位，裁剪不会重新分配已发现的槽位；末尾保留材质空间。相机高频更新与局部矩形动画在 rAF 中写 DOM，静止后停止循环；减少动态偏好会直接落定几何。返回墙面保留相机、焦点、分页和映射；410 提示主动刷新。

已运行的验收与剩余验证见 [VERIFICATION.md](./VERIFICATION.md)。
