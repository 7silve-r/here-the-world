# v1 接口索引

版本：`1.0.0-draft.2`。基础路径 `/api/v1`。以下描述目标接口，具体实现进度以代码为准。字段定义以 [OpenAPI](./openapi.json) 为准，业务语义见 [接口约定](./api-contract.md)。

37 个 JSON 业务接口的成功响应统一包裹 `{ data, requestId }`，下表返回类型指 `data`。2 个媒体读取入口直接返回文件或响应头。可匿名业务接口支持 Bearer，以返回当前用户互动状态。

## System

| 方法 | 路径 | 用途 | 身份 | 请求体 | 返回 | 额外要求 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/system/capabilities` | 读取发布与上传限制 | 公开 | — | Capabilities (200) | — |

## Auth

| 方法 | 路径 | 用途 | 身份 | 请求体 | 返回 | 额外要求 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/auth/csrf` | 初始化 CSRF 上下文 | 公开 | — | Csrf (200) | — |
| POST | `/auth/register` | 邮箱注册并发送验证邮件 | CSRF Cookie | RegisterInput | MessageAccepted (202) | CSRF；邮箱或用户名冲突返回 409 |
| POST | `/auth/verify-email` | 校验邮箱验证码 | CSRF Cookie | VerifyEmailInput | Ack (200) | CSRF |
| POST | `/auth/resend-verification` | 重发邮箱验证邮件 | CSRF Cookie | EmailInput | MessageAccepted (202) | CSRF |
| POST | `/auth/login` | 登录并建立会话 | CSRF Cookie | LoginInput | AuthSession (200) | CSRF |
| POST | `/auth/refresh` | 轮换刷新凭证并签发 access token | 刷新 Cookie | — | AuthSession (200) | CSRF |
| POST | `/auth/logout` | 撤销当前会话并清除刷新及媒体 Cookie | CSRF Cookie | — | Ack (200) | CSRF |
| POST | `/auth/forgot-password` | 发送密码重置邮件 | CSRF Cookie | EmailInput | MessageAccepted (202) | CSRF |
| POST | `/auth/reset-password` | 用邮件凭证重置密码 | CSRF Cookie | ResetPasswordInput | Ack (200) | CSRF |
| POST | `/auth/change-password` | 校验当前密码后修改密码 | Bearer | ChangePasswordInput | Ack (200) | CSRF |

## Profile

| 方法 | 路径 | 用途 | 身份 | 请求体 | 返回 | 额外要求 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/me` | 读取当前账号私有资料 | Bearer | — | Me (200) | — |
| PATCH | `/me` | 修改昵称简介与头像 | Bearer | ProfilePatch | Me (200) | — |
| GET | `/me/settings` | 读取个人设置 | Bearer | — | Settings (200) | — |
| PATCH | `/me/settings` | 修改个人设置 | Bearer | SettingsPatch | Settings (200) | — |
| GET | `/users/{userId}` | 读取公开个人主页资料 | 可匿名 | — | PublicUser (200) | — |
| GET | `/users/{userId}/works` | 读取用户已发布作品 | 可匿名 | — | WorkPage (200) | — |
| GET | `/me/works` | 读取我的作品及发布状态 | Bearer | — | WorkPage (200) | — |
| GET | `/me/bookmarks` | 读取我的收藏 | Bearer | — | WorkPage (200) | — |

## Feed

| 方法 | 路径 | 用途 | 身份 | 请求体 | 返回 | 额外要求 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/feed` | 读取随机但会话内稳定的作品流 | 可匿名 | — | FeedPage (200) | — |

## Works

| 方法 | 路径 | 用途 | 身份 | 请求体 | 返回 | 额外要求 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/works` | 创建作品草稿 | Bearer | WorkInput | WorkDetail (201) | Idempotency-Key |
| GET | `/works/{workId}` | 读取作品详情或作者自己的草稿 | 可匿名 | — | WorkDetail (200) | — |
| PUT | `/works/{workId}` | 完整保存草稿可编辑字段 | Bearer | WorkInput | WorkDetail (200) | If-Match |
| POST | `/works/{workId}/publish` | 校验并提交发布 | Bearer | — | WorkDetail (200/202) | If-Match |
| DELETE | `/works/{workId}` | 作者删除自己的作品 | Bearer | — | Ack (200) | If-Match |

## Interactions

| 方法 | 路径 | 用途 | 身份 | 请求体 | 返回 | 额外要求 |
| --- | --- | --- | --- | --- | --- | --- |
| PUT | `/works/{workId}/like` | 确保当前用户已点赞 | Bearer | — | InteractionState (200) | — |
| DELETE | `/works/{workId}/like` | 确保当前用户未点赞 | Bearer | — | InteractionState (200) | — |
| PUT | `/works/{workId}/bookmark` | 确保当前用户已收藏 | Bearer | — | InteractionState (200) | — |
| DELETE | `/works/{workId}/bookmark` | 确保当前用户未收藏 | Bearer | — | InteractionState (200) | — |

## Comments

| 方法 | 路径 | 用途 | 身份 | 请求体 | 返回 | 额外要求 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/works/{workId}/comments` | 按时间正序读取平层评论 | 可匿名 | — | CommentPage (200) | — |
| POST | `/works/{workId}/comments` | 发布评论 | Bearer | CommentInput | Comment (201) | Idempotency-Key |
| DELETE | `/comments/{commentId}` | 评论作者或作品作者删除评论 | Bearer | — | Ack (200) | — |

## Media

| 方法 | 路径 | 用途 | 身份 | 请求体 | 返回 | 额外要求 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/uploads` | 申请单文件直传地址 | Bearer | UploadInput | UploadTicket (201) | Idempotency-Key |
| POST | `/uploads/{uploadId}/complete` | 服务端校验对象并开始媒体处理 | Bearer | UploadCompleteInput | Asset (202) | — |
| DELETE | `/uploads/{uploadId}` | 取消尚未完成的上传 | Bearer | — | Ack (200) | — |
| GET | `/assets/{assetId}` | 读取媒体处理状态与可用资源 | 可匿名 | — | Asset (200) | — |
| POST | `/music-links/resolve` | 解析音乐分享链接并返回可用展示方式 | Bearer | MusicLinkInput | MusicLink (200) | — |
| GET / HEAD | `/media/public/assets/{assetId}/{variant}` | 读取当前公开资源 | 公开 | — | 文件或响应头 (200/206/304) | variant 为 file 或 thumbnail；支持 Range |
| GET / HEAD | `/media/private/assets/{assetId}/{variant}` | 读取本人私有资源 | 媒体 Cookie 或 Bearer | — | 文件或响应头 (200/206/304) | 检查会话与所有权；支持 Range；private, no-store |

所有路径参数均为不透明字符串。分页接口使用 `cursor`、`limit`（默认 24，上限 50）。头像、封面及文件通过通用上传接口处理，不另设 multipart 业务接口。
