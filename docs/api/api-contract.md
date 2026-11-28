# Here the World：前后端共同接口约定

版本：`1.0.0-draft.2`；整理日期：2026-11-20。此文档是前后端共同开发基线，包含 37 个 JSON 业务接口及 2 个媒体读取入口。媒体读取入口同时支持 GET 和 HEAD。具体实现进度以对应后端代码为准。

- [接口索引](./endpoint-index.md)：按模块列出方法、路径、身份、请求体、响应类型。
- [OpenAPI 3.1.1](./openapi.json)：字段、必填项、枚举、路径和状态码的机器可读契约。

结构字段以 OpenAPI 为准；本文补充跨字段校验、状态机、权限、分页和交互语义。发现冲突应先更新共同契约，再修改实现，不能由任一端自行增加另一套字段。

## 1. v1 范围和工程边界

平台支持图片、文字、视频、音频文件、音乐链接作品；一个作品占据一个空间槽位。访客可浏览公开作品和评论；已验证邮箱并登录的用户可发布、评论、点赞和收藏。

后端采用 Java / Spring Boot / MyBatis / MySQL，位于 `backend/`；前端采用 React / TypeScript / Vite，位于 `frontend/`。本文描述目标接口，不表示后端已经实现全部接口。共同契约变更需同步更新本目录的 OpenAPI、接口约定和接口索引。

`docs/api/openapi.json` 是共享契约源文件。前端据此生成接口类型并校验 Mock；后端打包使用 `backend/business/src/main/resources/openapi.json` 副本，两份 JSON 内容必须保持一致。可在 `frontend/` 执行 `npm run check:api` 检查生成类型是否同步。

v1 暂不包含关注、私信、通知、推荐算法、评论回复树、管理员后台、已发布作品内容编辑、邮箱更换及用户注销。这些属于后续扩展，不应自行加入当前接口依赖。账号设置包含资料、展示偏好和修改密码；邮箱在设置中只读。

新账号默认设置为 `theme=SYSTEM`、`motion=SYSTEM`、`autoPlayMedia=true`。motion=SYSTEM 跟随系统减少动态效果偏好，REDUCED 强制降低；autoPlayMedia=true 也不保证浏览器允许有声自动播放。

## 2. HTTP 和数据约定

| 项目 | 约定 |
| --- | --- |
| API 基础路径 | `/api/v1`，文档中的资源路径均在其下 |
| 开发环境 | 前端代理 `/api` 到本机后端；浏览器始终访问相对路径，避免跨站 Cookie 差异 |
| 请求与响应 | UTF-8；JSON 请求使用 `Content-Type: application/json` |
| ID | 不透明字符串，包括用户、作品、文件、评论、上传和会话 ID；不得转为 JS number |
| 时间 | RFC 3339 UTC，例如 `2026-10-21T03:00:00Z`；前端按本地时区展示 |
| 枚举 | 大写英文，如 `PUBLISHED`、`MUSIC_LINK` |
| JSON 字段 | camelCase；未知请求字段返回 422，不静默丢弃 |
| null | 响应中契约标为可空的字段显式返回 null；空集合返回 `[]` |
| PATCH | 缺席字段不变；显式 null 仅允许契约支持的字段，如 avatarAssetId |
| PUT 草稿 | 完整提交 WorkInput，不是部分更新 |
| 文本 | JSON 文字按纯文本处理，不接收任意 HTML；服务端裁去标题/正文/评论首尾空白，拒绝必填字段纯空白；密码不裁剪 |
| 字符长度 | 按 Unicode code point 计，前端可用 `Array.from(value).length`；后端使用相同规则 |
| 缓存 | 鉴权、私有资料及含 viewer 状态的响应使用 `Cache-Control: private, no-store` |
| 请求追踪 | 响应头 `X-Request-Id` 与 JSON requestId 一致 |

成功响应包括 DELETE，均保留 JSON，不使用 204：

```json
{
  "data": { "success": true },
  "requestId": "req_demo_01"
}
```

失败响应直接返回错误对象，不包裹 data；HTTP 状态必须与错误匹配：

```json
{
  "code": "VALIDATION_FAILED",
  "message": "作品标题不能为空",
  "requestId": "req_demo_02",
  "details": [
    { "field": "title", "reason": "REQUIRED", "message": "请输入作品标题" }
  ]
}
```

无字段错误时 details 返回 `[]`。前端依赖 code 和 details 做判断，不用中文 message 匹配业务分支。

## 3. 身份、Cookie 和邮箱流程

### 3.1 会话

access token 是 JWT，通过 `Authorization: Bearer <token>` 传输，前端只保存在内存。默认有效期 15 分钟，实际以响应 `expiresIn`（秒）为准。

refresh token 使用服务端可撤销的随机凭证，存入 `htw_refresh` Cookie：生产环境 `HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth`，不设置 Domain，最长有效期 30 天。每次刷新轮换，由后端维护会话记录和失效状态。本机开发允许通过明确的 development 配置关闭 Secure，生产必须开启；优先经 HTTPS 或同源反向代理部署。

前端不读取 refresh token、不尝试读取 Set-Cookie，也不把 JWT、密码、验证码和重置凭证写入 localStorage、持久化状态或日志。刷新页面后通过 refresh 恢复会话。

私有媒体使用 `htw_media` Cookie，属性为 HttpOnly、SameSite=Lax、Path=/api/v1/media/private/，生产环境启用 Secure。登录和刷新会话成功时设置该 Cookie，退出或成功修改密码时清除。Cookie 携带用途为 media-read 的签名凭证，绑定 userId、sessionId，其到期时间与现有会话剩余有效期一致。每次私有媒体 GET/HEAD 请求检查凭证、会话是否撤销或过期，以及资源所有权。业务 API 使用 Bearer 身份，媒体 Cookie 的识别范围限定在私有媒体读取入口。

公开 GET 可以不带 token；若客户端主动带了过期或无效 token，返回 401，不能静默当访客。没有 token 时公开响应 viewer 的三个布尔值均为 false。其他用户的公开资料不得含邮箱。

### 3.2 CSRF

首次启动执行 `GET /auth/csrf`，带 `credentials: include`。后端设置 `htw_csrf_context` HttpOnly、SameSite=Lax、Path=/api/v1/auth 的上下文 Cookie，并在 data 返回短期 csrfToken 和 expiresAt；生产 Cookie 同样 Secure。

所有 `/auth/*` POST 均带 Cookie 和 `X-CSRF-Token`，包括注册、登录、刷新、退出、验证码及密码操作。令牌必须与 Cookie 上下文匹配，服务端同时校验浏览器 Origin；不能只检查请求头存在。CSRF 上下文独立于 access token，不因 refresh token 每次轮换而失效。具体安全存储方案由后端实现。

`change-password` 还必须携带 Bearer。其余使用 Bearer 的业务写接口不依赖 Cookie 身份。业务 API 不接受 refresh token 作为 Authorization。

### 3.3 邮箱注册

```text
GET /auth/csrf
POST /auth/register { email, password, username, displayName }
  -> 202，待验证账号，后台任务发送验证码
POST /auth/verify-email { email, code }
  -> 200，邮箱验证成功
POST /auth/login { email, password }
  -> 200，access token + Me；响应设置 refresh Cookie
```

邮箱按 trim 后小写值匹配和唯一约束。username 为 3–24 位小写字母、数字或下划线；v1 不可修改；昵称 displayName 可修改。密码为 10–128 个 code point。

验证码 6 位数字、默认 10 分钟有效，重发间隔至少 60 秒，并按账号及来源限制尝试次数；重发后旧验证码作废。验证只激活账号，不自动登录。已验证同邮箱的 verify 请求可幂等成功；错误、过期和尝试次数超限分别返回明确 code。

register 对已注册邮箱返回 409 EMAIL_TAKEN，提示“邮箱已注册，请登录或找回密码”。用户名冲突时返回 409 USERNAME_TAKEN。前端在注册页展示错误，成功受理新账号注册后进入邮箱验证页。

resend-verification 和 forgot-password 对已存在或不存在的邮箱返回相同 202 形状，避免邮箱枚举。202 只表示请求被接受，不表示邮件已经投递。SMTP 不可用且请求无法可靠排队时返回 503。

### 3.4 刷新、退出与重置密码

遇到 401 ACCESS_TOKEN_EXPIRED 时，前端对所有并发请求复用一次 refresh，再各自最多重试一次；刷新失败则清理内存会话并进入登录流程。刷新不得递归触发刷新。不同标签页也要用浏览器锁或消息协调刷新，避免轮换竞争。

退出撤销当前会话并清除 refresh Cookie；没有 refresh Cookie 时 logout 仍成功，但 CSRF 上下文必须有效。后端以 JWT 中的会话标识验证会话是否仍可用，使退出、改密和重置后的旧 access token 也失效。

忘记密码：forgot-password 发送单次限时重置凭证，默认 30 分钟。邮件链接采用前端 `/reset-password#token=...`；前端提取后立即从地址栏移除 fragment，POST reset-password 提交 token 与 newPassword。成功后撤销所有会话并回到登录，不直接签发新 token。

修改密码提交 currentPassword 与 newPassword，成功后同样撤销所有会话并清除 Cookie。CSRF_INVALID 可重新获取一次 CSRF；明确拒绝的请求可以再发一次，网络超时的非幂等请求不能盲目重放。

## 4. 核心资源字段

完整字段和必填项见 OpenAPI；以下说明资源关系。

| 资源 | 核心字段 | 说明 |
| --- | --- | --- |
| PublicUser | id、username、displayName、bio、avatarUrl、publishedWorkCount | 公开用户信息 |
| Me | PublicUser 全部字段、email、emailVerified | 仅自己可见，成功会话 emailVerified 恒为 true |
| WorkCard | id、kind、status、version、title、excerpt、author、cover、stats、viewer、createdAt、publishedAt | 首页和列表的轻量卡片，不直接携带视频/音频文件 |
| WorkDetail | WorkCard 全部字段、body、assets、coverAsset、musicLink、updatedAt、failureCode | 详情与作者草稿使用；assets 按 mediaAssetIds 提交顺序返回，封面单独在 coverAsset |
| WorkStats | likeCount、bookmarkCount、commentCount | 非负整数 |
| ViewerState | liked、bookmarked、isOwner | 当前访问者的状态；不要根据计数猜测 |
| Asset | id、purpose、mediaType、status、mimeType、sizeBytes、url、thumbnailUrl、尺寸/时长、failureCode | READY 前 url/thumbnailUrl 允许为 null；不可播放资源不伪造 URL |
| MusicLink | id、provider、sourceUrl、title、artist、coverUrl、metadataStatus、playback | 原始链接和可用播放模式 |
| Comment | id、workId、author、body、canDelete、createdAt | 平层评论，无 parentId |

`cover` 是适合卡片显示的轻量图像信息，含 url、width、height、blurHash；允许无封面。`excerpt` 由后端生成，最大 280 个 code point。布局跨度、世界坐标、当前矩阵块、hover 状态均由前端决定，API 不保存这些瞬时视觉信息。

## 5. 作品草稿和发布

### 5.1 输入与类型规则

创建草稿和完整保存草稿都使用：

```json
{
  "kind": "IMAGE",
  "title": "城市的另一面",
  "body": "雨停后的街道。",
  "mediaAssetIds": ["asset_photo_01", "asset_photo_02"],
  "coverAssetId": null,
  "musicLinkId": null
}
```

| kind | 发布所需媒体 | body | 封面 |
| --- | --- | --- | --- |
| TEXT | mediaAssetIds 必须为空，musicLinkId 为 null | 去首尾空白后至少 1 字 | 可选 WORK_COVER 图片；否则文字卡 |
| IMAGE | 1–9 个 WORK_MEDIA / IMAGE | 可为空 | 自定义 WORK_COVER，或第一张图片 |
| VIDEO | 恰好 1 个 WORK_MEDIA / VIDEO | 可为空 | 自定义 WORK_COVER，或服务端生成视频缩略图 |
| AUDIO | 恰好 1 个 WORK_MEDIA / AUDIO | 可为空 | 自定义 WORK_COVER，或主题占位图 |
| MUSIC_LINK | mediaAssetIds 为空，musicLinkId 必填 | 可为空 | 自定义 WORK_COVER，或已解析音乐封面；都没有则占位 |

所有类型 title 必填，最多 120 字，body 最多 20000 字。非 MUSIC_LINK 的 musicLinkId 必须为 null。封面和头像必须是图片。kind 创建后不可改变。

草稿允许缺少主媒体或暂未完成处理，但媒体引用必须属于本人、purpose 和类型正确，不能引用他人的私有 Asset 或 MusicLink。发布时执行完整数量和内容校验。作品不支持同时混入图片、视频、音频多种主媒体。

### 5.2 状态机

```text
POST /works -> DRAFT
DRAFT --publish，全资源 READY--> PUBLISHED
DRAFT --publish，有资源待处理--> PROCESSING
PROCESSING --后台全部完成--> PUBLISHED
PROCESSING --后台处理失败--> FAILED
FAILED --PUT 保存修正--> DRAFT
作者 DELETE -> 物理删除作品及评论、点赞、收藏、媒体关联，退出公开列表
```

草稿和 FAILED 可保存；PROCESSING 和 PUBLISHED 不能修改内容，返回 WORK_NOT_EDITABLE。已发布作品编辑不在 v1 内。无效或已经 FAILED 的资源不能直接进入发布处理，返回 ASSET_NOT_READY 或具体校验错误；修正或更换资源后重试。

publish 的 200 表示已经 PUBLISHED；202 表示已经进入 PROCESSING，前端轮询详情到 PUBLISHED/FAILED。只有 PUBLISHED 出现在公开随机流、个人公开作品、点赞收藏和评论接口中。作者可读所有未删除状态；其他人访问非公开内容统一 404。

### 5.3 版本控制

GET 详情及创建、保存、发布响应包含 `version`，并返回 `ETag: "v3"` 形式的头。保存、发布和删除要求 `If-Match: "v3"`；缺失返回 428 PRECONDITION_REQUIRED，过期返回 412 VERSION_CONFLICT。后端更新、删除均包含旧版本条件，后台状态变化也增加版本。删除成功只返回 Ack，不返回资源 ETag。

前端可直接使用当前 version 构造 If-Match。412 时重新拉取并提示冲突，不直接覆盖。重复 publish 在版本匹配且状态已是 PROCESSING/PUBLISHED 时返回当前状态，不重复安排任务。作品删除先校验作者与版本，在同一事务删除关联数据及主体，不保留 tombstone；重复删除或删除不存在的作品返回 404 WORK_NOT_FOUND。已释放的媒体仅在没有其他引用时由维护任务回收，不能随作品直接删除共享文件。

## 6. 随机作品流和其他分页

### 6.1 分页结构

```json
{
  "data": {
    "items": [],
    "nextCursor": "opaque_server_cursor",
    "hasMore": true,
    "feedSession": {
      "id": "feed_demo_01",
      "expiresAt": "2026-10-21T03:30:00Z"
    }
  },
  "requestId": "req_demo_03"
}
```

只有 FeedPage 带 feedSession；WorkPage 和 CommentPage 只有 items、nextCursor、hasMore。首次请求不传 cursor；limit 默认 24，范围 1–50。不再提供 page/offset 参数。

hasMore=false 时 nextCursor 必须为 null；true 时必须为非空游标。游标为空的首请求与末页 null 不可混用。每页可能因内容删除而不足 limit；前端按 hasMore 判断，不以 items.length 判断。即使返回空 items，只要 hasMore=true，下一个游标也必须前进。

### 6.2 随机快照

`GET /feed?limit=24` 创建随机种子与“本次已发布作品截止时间”快照，30 分钟有效；后续页游标携带或关联种子、截止时间、位置及过滤条件，并由后端防篡改。随机策略不考虑用户偏好。后端可采用有期限的抽样 ID 快照或预生成随机索引；不要每页对整表重新 ORDER BY RAND()。

同一会话顺序稳定，跨页不重复；新发布作品等用户主动刷新后进入。删除或不可见作品跳过；正文和互动计数可更新，不保证整段快照的数据完全冻结。

v1 只有公开内容，游标不绑定登录身份；登录前后可继续同一随机会话，viewer 按每次请求身份计算。游标绑定路径、过滤条件及分页位置，不可移用于别的列表。过期返回 410 FEED_SESSION_EXPIRED；前端保留已显示墙面并提示刷新，不在用户拖动中突然重排。

### 6.3 二维空间与一维作品序列

后端只提供有稳定顺序的作品序列，不接收 x/y/block 参数。前端为发现的空间槽位保存稳定映射，维护已用 workId 集合，缺内容时加载下一页，不能因每次裁剪变化重新按数组下标给旧槽位分配作品。

进入方向尚无内容时可以显示骨架并请求更多。数据耗尽后允许保留空材质或明确的占位槽位，仍可拖动空间；v1 不用已看过作品冒充新的内容。将来若需要循环铺陈，应显式区分作品身份和视觉副本身份。

个人公开作品按 publishedAt、id 降序；我的作品按 createdAt、id 降序；收藏按收藏时间、关系 ID 降序；评论按 createdAt、id 升序。相同过滤条件下使用稳定游标；改变 status 等条件必须重开分页。

## 7. 文件上传和媒体处理

### 7.1 统一上传协议

```text
POST /uploads { purpose, fileName, contentType, sizeBytes }
  + Idempotency-Key
  -> uploadId、assetId、uploadUrl、method=PUT、headers、expiresAt、maxBytes

PUT uploadUrl
  -> 按 headers 原样发送二进制 File，浏览器上传进度
  -> 不带业务 Authorization，不发业务 Cookie，不用 FormData

POST /uploads/{uploadId}/complete { sizeBytes }
  -> 202 + Asset

GET /assets/{assetId}
  -> 轮询至 READY / FAILED
```

uploadUrl 由后端动态返回，可以是对象存储签名地址，也可以是本地存储适配器的签名二进制接收地址；它是上传协议的一部分，不另加前端猜测的上传路径。前端以返回的 headers 和 method 为准。该 URL 是短期上传凭证，默认 15 分钟有效，不应写日志或持久化。

传输成功后必须调用 complete；后端校验实际对象存在、大小、真实类型及上传归属，再将媒体处理可靠入队。前端传来的 sizeBytes 只是声明，不能当作可信存储检查。重复 complete 返回当前 Asset，不重复转码。

Asset 状态：CREATED → UPLOADED → PROCESSING → READY / FAILED；有些图片可较快直接 READY。未完成上传可以 cancel；完成后 cancel 返回 409 UPLOAD_ALREADY_COMPLETED。过期与长期未绑定作品的孤立资源由后台清理，前端不需要资产删除接口。已绑定资源不能被清理任务误删。

GET Asset 对未公开资源只允许拥有者。公开作品的 READY 媒体及已设置的公开头像可以被访客读取。失败时返回 failureCode，除 CREATED 外可记录实际 sizeBytes/MIME。

媒体 URL 由后端返回，允许同域相对 URI。公开媒体使用 `/api/v1/media/public/assets/{assetId}/{variant}`，私有媒体使用 `/api/v1/media/private/assets/{assetId}/{variant}`，variant 为 file 或 thumbnail。相同资源在公开性和文件版本保持稳定时使用相同 URL。私有资源由资源所有者通过有效媒体 Cookie 或 Bearer 身份读取。公开读取检查当前公开引用。两种入口支持 HEAD 和 Range 分段读取。上传继续使用有时限的上传票据。

公开媒体响应使用 `Cache-Control: public, max-age=60, must-revalidate`，私有媒体使用 `private, no-store`。作品删除后，后端收到旧公开地址的请求会重新检查公开引用，浏览器已经取得的公开响应可在剩余 60 秒新鲜期内复用。业务 JSON 响应继续按其隐私规则设置缓存。

媒体 Cookie 在现有会话到期前支持持续读取。私有媒体读取失败时，前端可以复用已有刷新会话流程更新 Cookie，重新获取资源状态后重试一次，并保存播放进度、暂停状态与播放设置。稳定地址重试时允许复用同一 URL。HttpOnly Cookie 的实际携带和撤销通过真实后端联调验证，Mock 模拟业务状态与响应。

### 7.2 初始限制

以下为 v1 初始值，后端可通过 capabilities 调整；前端表单读 capabilities，服务端独立执行限制。

| purpose / 类型 | MIME | 单文件上限 |
| --- | --- | --- |
| WORK_MEDIA / IMAGE | image/jpeg、image/png、image/webp、image/gif | 20 MiB |
| WORK_MEDIA / VIDEO | video/mp4、video/webm | 500 MiB |
| WORK_MEDIA / AUDIO | audio/mpeg、audio/mp4、audio/wav、audio/x-wav、audio/ogg、audio/flac | 100 MiB |
| WORK_COVER / IMAGE | image/jpeg、image/png、image/webp | 10 MiB |
| AVATAR / IMAGE | image/jpeg、image/png、image/webp | 5 MiB |

1 MiB = 1048576 bytes。前端检查文件扩展名与浏览器 MIME 仅用于提示，后端按真实内容判定；浏览器无法直接播放的已接受视频/音频需后端转成可播放资源，转码失败明确 FAILED。v1 使用单文件直传，不承诺断点续传和分片。

轮询建议 1s 起步、逐渐退避到 5s；页面隐藏暂停，卸载取消。连续等待两分钟后显示“仍在处理”并允许稍后返回，不将超时自动当作失败。

## 8. 音乐分享链接

POST /music-links/resolve 的 input 可为 HTTPS 地址或带一条 HTTPS 地址的分享文案。后端提取、规范化链接，返回 MusicLink ID，发布作品时传 musicLinkId。

解析只执行本地地址校验与存储，不访问分享页、跟随短链跳转或抓取音频。完整 URL 优先保留；分享文案支持常见引号、紧邻 URL 的成对括号及句末标点。HTTP 等非 HTTPS 地址、带 userinfo 的地址和包含多条链接的文案返回 400 MUSIC_LINK_INVALID。域名按 IDN 转为 ASCII 并小写，移除域名末尾的根标签点及默认 443 端口，规范化路径的点段；查询参数和片段保留，Unicode 内容编码为 ASCII URI。input 和规范化后的 sourceUrl 均不得超过 4096 字符，输入超限按共享校验返回 422，规范化后超限返回 400 MUSIC_LINK_INVALID。

MusicLink.provider 初始枚举为 NETEASE、QQ、KUGOU、SPOTIFY、APPLE_MUSIC、OTHER。provider 只表示来源，不保证元数据解析或嵌入能力。支持的实际嵌入来源由 capabilities.musicEmbedProviders 返回，默认可为空。

playback.mode=EXTERNAL 时 embedUrl=null，前端展示原平台打开按钮；EMBED 时只加载后端返回且允许的官方嵌入 URL。不能将任意音乐分享页地址填入 audio.src，不承诺解析原始音频文件。元数据不可获取时返回 PARTIAL 和可空字段，作品仍可发布。

后端仅对允许的官方地址拉取元数据，校验跳转与最终目标；未知 HTTPS 地址按 EXTERNAL 处理，不进行任意网页抓取。前端不执行分享文案中的 HTML 或脚本。解析结果属于当前用户，其他用户不能拿未发布的 musicLinkId 直接创建作品。

## 9. 社交互动与并发语义

| 操作 | 方法 | 重复请求 |
| --- | --- | --- |
| 点赞 | PUT /works/{id}/like | 保证已点赞，不重复增加计数 |
| 取消点赞 | DELETE /works/{id}/like | 保证未点赞，不重复减少计数 |
| 收藏 | PUT /works/{id}/bookmark | 保证已收藏 |
| 取消收藏 | DELETE /works/{id}/bookmark | 保证未收藏 |

响应 InteractionState 返回该用户 liked、bookmarked 以及两个最新计数。后端对 `(userId, workId)` 唯一约束，关系与计数在同一事务内变更，不能“先查再插”作为唯一防重措施。

前端允许乐观更新，但同一作品同一动作应串行化或合并到最后意图，防止先点赞后取消的响应乱序覆盖；失败要回滚。列表与详情共享同一作品缓存，成功后同步状态。响应计数是该次操作时的快照，后续其他用户操作导致变化属于正常情况。

评论最多 2000 字，平层展示。创建评论使用 Idempotency-Key；只有评论作者或作品作者可删除，后端校验权限并给前端 canDelete。评论采用物理删除，不保留正文或软删除行；重复删除返回 404 COMMENT_NOT_FOUND，计数从剩余关系计算。作品不可见时相关互动统一 404 或 WORK_NOT_PUBLISHED（作者操作未发布作品时）。

## 10. 幂等、限流与错误码

创建草稿、申请上传、创建评论要求 `Idempotency-Key: <UUID>`。同一用户、方法、资源路径、key 的记录保留 24 小时；同请求体重试返回原业务结果，不重复创建；请求体不同返回 IDEMPOTENCY_CONFLICT。处理中返回 409 REQUEST_IN_PROGRESS，带 Retry-After。校验失败不消耗幂等 key；其他失败是否重试按具体 code。

创建上传返回的旧凭证若已过期，需要新 key 申请新上传，不用旧 key 期待获得另一套凭证。修改后重新提交是一项新意图，使用新 key。后端超时前是否成功未知时保留原 key 重试。

| HTTP | code 示例 | 前端处理 |
| --- | --- | --- |
| 400 | BAD_REQUEST、INVALID_CURSOR、MUSIC_LINK_INVALID | 参数或游标有问题，给出具体提示 |
| 401 | AUTH_REQUIRED、ACCESS_TOKEN_EXPIRED、TOKEN_INVALID、SESSION_REVOKED、REFRESH_INVALID、INVALID_CREDENTIALS | 只有 ACCESS_TOKEN_EXPIRED 自动刷新；无效/撤销会话清理登录态；登录凭证错误就地提示 |
| 403 | EMAIL_NOT_VERIFIED、FORBIDDEN、CSRF_INVALID | 引导验证、无权限或重新获取 CSRF |
| 404 | WORK_NOT_FOUND、USER_NOT_FOUND、ASSET_NOT_FOUND、COMMENT_NOT_FOUND、UPLOAD_NOT_FOUND | 显示不可用，不泄露私有内容存在性 |
| 409 | EMAIL_TAKEN、USERNAME_TAKEN、IDEMPOTENCY_CONFLICT、REQUEST_IN_PROGRESS、WORK_NOT_EDITABLE、WORK_NOT_PUBLISHED、WORK_KIND_IMMUTABLE、UPLOAD_ALREADY_COMPLETED | 展示冲突；REQUEST_IN_PROGRESS 可按 Retry-After 重试 |
| 410 | FEED_SESSION_EXPIRED、UPLOAD_EXPIRED、VERIFICATION_CODE_EXPIRED、RESET_TOKEN_EXPIRED | 提示刷新作品流、重新上传或重发凭证 |
| 412 | VERSION_CONFLICT | 拉取新版本，提示用户处理 |
| 413 | FILE_TOO_LARGE | 提示大小限制 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 提示格式限制 |
| 422 | VALIDATION_FAILED、ASSET_NOT_READY、ASSET_TYPE_MISMATCH、ASSET_OWNERSHIP_INVALID、VERIFICATION_CODE_INVALID、RESET_TOKEN_INVALID | 就地表单提示或等待/更换资产 |
| 428 | PRECONDITION_REQUIRED | 客户端缺 If-Match，应修复调用 |
| 429 | RATE_LIMITED、VERIFICATION_ATTEMPTS_EXCEEDED | 读取 Retry-After 秒数并倒计时 |
| 500 | INTERNAL_ERROR | 通用错误，保留 requestId 供排查 |
| 503 | SERVICE_UNAVAILABLE、MAIL_SERVICE_UNAVAILABLE | 可重试提示，尊重 Retry-After |

上述 code 是公共集合，不是穷举每个接口的唯一状态。details.reason 采用 REQUIRED、TOO_LONG、INVALID_FORMAT、NOT_ALLOWED、OUT_OF_RANGE 等稳定英文值。浏览器与网络层错误由 API 客户端转成独立 NETWORK_ERROR，不伪造成服务端 code。

## 11. 前后端联调顺序与验收

1. capabilities、统一响应、异常和 CSRF。
2. 注册、验证、登录、续期、资料、设置。
3. WorkCard、随机分页、作品详情，以及首页槽位映射。
4. 上传票据、二进制传输、complete、资产轮询、草稿、发布。
5. 点赞收藏、评论、个人作品和收藏列表。
6. 过期、重试、权限、并发点击、版本冲突、上传失败及跨页去重。

前端 Mock 与真实后端必须使用同一路径、方法、输入、枚举、状态和响应封装。Mock 至少模拟一次验证未完成、401 刷新、媒体 PROCESSING→READY、作品 PROCESSING→PUBLISHED、412 冲突、429、末页和随机会话过期。不能只返回永远成功的静态数组。

后端发布前必须用事务、唯一约束、条件更新和可靠任务派发保证协议语义；邮件/媒体任务允许重试，但结果不得重复生效。后台发布任务需要检查作品未删除及版本/发布任务身份，防止删除后又被异步任务重新发布。推荐算法、对象存储品牌和队列实现可替换，但不应改变上述 HTTP 契约。

## 12. 协议依据

本项目的具体字段和生命周期是产品设计决定，不是标准自动规定。机器描述使用 [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)；JWT 格式参考 [RFC 7519](https://www.rfc-editor.org/rfc/rfc7519.html)；浏览器 Cookie 属性参考 [MDN Set-Cookie](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie)。
