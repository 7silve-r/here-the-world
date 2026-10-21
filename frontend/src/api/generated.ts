// Generated from docs/api/openapi.json (1.0.0-draft.1). DO NOT EDIT.
export type Error = { "code": string; "message": string; "requestId": string; "details": Array<{ "field": string | null; "reason": string; "message": string }> };
export type Ack = { "success": true };
export type MessageAccepted = { "accepted": true; "resendAfterSeconds": number };
export type Csrf = { "csrfToken": string; "expiresAt": string };
export type Cover = { "url": string; "width": number | null; "height": number | null; "blurHash": string | null };
export type PublicUser = { "id": string; "username": string; "displayName": string; "bio": string; "avatarUrl": string | null; "publishedWorkCount": number; "createdAt": string };
export type Me = { "id": string; "username": string; "displayName": string; "bio": string; "avatarUrl": string | null; "publishedWorkCount": number; "createdAt": string; "email": string; "emailVerified": true };
export type AuthSession = { "accessToken": string; "tokenType": "Bearer"; "expiresIn": number; "user": Me };
export type RegisterInput = { "email": string; "password": string; "username": string; "displayName": string };
export type EmailInput = { "email": string };
export type VerifyEmailInput = { "email": string; "code": string };
export type LoginInput = { "email": string; "password": string };
export type ResetPasswordInput = { "token": string; "newPassword": string };
export type ChangePasswordInput = { "currentPassword": string; "newPassword": string };
export type ProfilePatch = { "displayName"?: string; "bio"?: string; "avatarAssetId"?: string | null };
export type Settings = { "theme": "SYSTEM" | "LIGHT" | "DARK"; "motion": "SYSTEM" | "REDUCED"; "autoPlayMedia": boolean };
export type SettingsPatch = { "theme"?: "SYSTEM" | "LIGHT" | "DARK"; "motion"?: "SYSTEM" | "REDUCED"; "autoPlayMedia"?: boolean };
export type Asset = { "id": string; "purpose": "WORK_MEDIA" | "WORK_COVER" | "AVATAR"; "mediaType": "IMAGE" | "VIDEO" | "AUDIO"; "status": "CREATED" | "UPLOADED" | "PROCESSING" | "READY" | "FAILED"; "mimeType": string; "sizeBytes": number; "url": string | null; "thumbnailUrl": string | null; "width": number | null; "height": number | null; "durationMs": number | null; "failureCode": string | null; "createdAt": string };
export type UploadInput = { "purpose": "WORK_MEDIA" | "WORK_COVER" | "AVATAR"; "fileName": string; "contentType": string; "sizeBytes": number };
export type UploadTicket = { "uploadId": string; "assetId": string; "uploadUrl": string; "method": "PUT"; "headers": { [key: string]: string }; "expiresAt": string; "maxBytes": number };
export type UploadCompleteInput = { "sizeBytes": number };
export type MusicLinkInput = { "input": string };
export type MusicLink = { "id": string; "provider": "NETEASE" | "QQ" | "KUGOU" | "SPOTIFY" | "APPLE_MUSIC" | "OTHER"; "sourceUrl": string; "title": string | null; "artist": string | null; "coverUrl": string | null; "metadataStatus": "READY" | "PARTIAL"; "playback": { "mode": "EXTERNAL" | "EMBED"; "embedUrl": string | null } };
export type WorkInput = { "kind": "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "MUSIC_LINK"; "title": string; "body": string; "mediaAssetIds": Array<string>; "coverAssetId": string | null; "musicLinkId": string | null };
export type WorkStats = { "likeCount": number; "bookmarkCount": number; "commentCount": number };
export type ViewerState = { "liked": boolean; "bookmarked": boolean; "isOwner": boolean };
export type WorkCard = { "id": string; "kind": "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "MUSIC_LINK"; "status": "DRAFT" | "PROCESSING" | "PUBLISHED" | "FAILED"; "version": number; "title": string; "excerpt": string; "author": PublicUser; "cover": Cover | null; "stats": WorkStats; "viewer": ViewerState; "createdAt": string; "publishedAt": string | null };
export type WorkDetail = { "id": string; "kind": "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "MUSIC_LINK"; "status": "DRAFT" | "PROCESSING" | "PUBLISHED" | "FAILED"; "version": number; "title": string; "excerpt": string; "author": PublicUser; "cover": Cover | null; "stats": WorkStats; "viewer": ViewerState; "createdAt": string; "publishedAt": string | null; "body": string; "assets": Array<Asset>; "coverAsset": Asset | null; "musicLink": MusicLink | null; "updatedAt": string; "failureCode": string | null };
export type InteractionState = { "workId": string; "liked": boolean; "bookmarked": boolean; "likeCount": number; "bookmarkCount": number };
export type CommentInput = { "body": string };
export type Comment = { "id": string; "workId": string; "author": PublicUser; "body": string; "canDelete": boolean; "createdAt": string };
export type WorkPage = { "items": Array<WorkCard>; "nextCursor": string | null; "hasMore": boolean };
export type CommentPage = { "items": Array<Comment>; "nextCursor": string | null; "hasMore": boolean };
export type FeedPage = { "items": Array<WorkCard>; "nextCursor": string | null; "hasMore": boolean; "feedSession": { "id": string; "expiresAt": string } };
export type UploadRule = { "purpose": "WORK_MEDIA" | "WORK_COVER" | "AVATAR"; "mediaType": "IMAGE" | "VIDEO" | "AUDIO"; "allowedMimeTypes": Array<string>; "maxBytes": number };
export type Capabilities = { "uploadRules": Array<UploadRule>; "maxImagesPerWork": number; "maxTitleLength": number; "maxBodyLength": number; "maxCommentLength": number; "verificationCodeTtlSeconds": number; "resendAfterSeconds": number; "musicEmbedProviders": Array<string> };
export interface Operations {
getCapabilities: { method: "GET"; path: "/system/capabilities"; input: undefined; output: Capabilities };
getCsrf: { method: "GET"; path: "/auth/csrf"; input: undefined; output: Csrf };
register: { method: "POST"; path: "/auth/register"; input: RegisterInput; output: MessageAccepted };
verifyEmail: { method: "POST"; path: "/auth/verify-email"; input: VerifyEmailInput; output: Ack };
resendVerification: { method: "POST"; path: "/auth/resend-verification"; input: EmailInput; output: MessageAccepted };
login: { method: "POST"; path: "/auth/login"; input: LoginInput; output: AuthSession };
refreshSession: { method: "POST"; path: "/auth/refresh"; input: undefined; output: AuthSession };
logout: { method: "POST"; path: "/auth/logout"; input: undefined; output: Ack };
forgotPassword: { method: "POST"; path: "/auth/forgot-password"; input: EmailInput; output: MessageAccepted };
resetPassword: { method: "POST"; path: "/auth/reset-password"; input: ResetPasswordInput; output: Ack };
changePassword: { method: "POST"; path: "/auth/change-password"; input: ChangePasswordInput; output: Ack };
getMe: { method: "GET"; path: "/me"; input: undefined; output: Me };
updateMe: { method: "PATCH"; path: "/me"; input: ProfilePatch; output: Me };
getSettings: { method: "GET"; path: "/me/settings"; input: undefined; output: Settings };
updateSettings: { method: "PATCH"; path: "/me/settings"; input: SettingsPatch; output: Settings };
getUser: { method: "GET"; path: "/users/{userId}"; input: undefined; output: PublicUser };
listUserWorks: { method: "GET"; path: "/users/{userId}/works"; input: undefined; output: WorkPage };
listMyWorks: { method: "GET"; path: "/me/works"; input: undefined; output: WorkPage };
listMyBookmarks: { method: "GET"; path: "/me/bookmarks"; input: undefined; output: WorkPage };
getFeed: { method: "GET"; path: "/feed"; input: undefined; output: FeedPage };
createWork: { method: "POST"; path: "/works"; input: WorkInput; output: WorkDetail };
getWork: { method: "GET"; path: "/works/{workId}"; input: undefined; output: WorkDetail };
updateWork: { method: "PUT"; path: "/works/{workId}"; input: WorkInput; output: WorkDetail };
deleteWork: { method: "DELETE"; path: "/works/{workId}"; input: undefined; output: Ack };
publishWork: { method: "POST"; path: "/works/{workId}/publish"; input: undefined; output: WorkDetail };
likeWork: { method: "PUT"; path: "/works/{workId}/like"; input: undefined; output: InteractionState };
unlikeWork: { method: "DELETE"; path: "/works/{workId}/like"; input: undefined; output: InteractionState };
bookmarkWork: { method: "PUT"; path: "/works/{workId}/bookmark"; input: undefined; output: InteractionState };
unbookmarkWork: { method: "DELETE"; path: "/works/{workId}/bookmark"; input: undefined; output: InteractionState };
listComments: { method: "GET"; path: "/works/{workId}/comments"; input: undefined; output: CommentPage };
createComment: { method: "POST"; path: "/works/{workId}/comments"; input: CommentInput; output: Comment };
deleteComment: { method: "DELETE"; path: "/comments/{commentId}"; input: undefined; output: Ack };
createUpload: { method: "POST"; path: "/uploads"; input: UploadInput; output: UploadTicket };
completeUpload: { method: "POST"; path: "/uploads/{uploadId}/complete"; input: UploadCompleteInput; output: Asset };
cancelUpload: { method: "DELETE"; path: "/uploads/{uploadId}"; input: undefined; output: Ack };
getAsset: { method: "GET"; path: "/assets/{assetId}"; input: undefined; output: Asset };
resolveMusicLink: { method: "POST"; path: "/music-links/resolve"; input: MusicLinkInput; output: MusicLink };
}
export const endpoints = {
  "getCapabilities": {
    "path": "/system/capabilities",
    "method": "GET"
  },
  "getCsrf": {
    "path": "/auth/csrf",
    "method": "GET"
  },
  "register": {
    "path": "/auth/register",
    "method": "POST"
  },
  "verifyEmail": {
    "path": "/auth/verify-email",
    "method": "POST"
  },
  "resendVerification": {
    "path": "/auth/resend-verification",
    "method": "POST"
  },
  "login": {
    "path": "/auth/login",
    "method": "POST"
  },
  "refreshSession": {
    "path": "/auth/refresh",
    "method": "POST"
  },
  "logout": {
    "path": "/auth/logout",
    "method": "POST"
  },
  "forgotPassword": {
    "path": "/auth/forgot-password",
    "method": "POST"
  },
  "resetPassword": {
    "path": "/auth/reset-password",
    "method": "POST"
  },
  "changePassword": {
    "path": "/auth/change-password",
    "method": "POST"
  },
  "getMe": {
    "path": "/me",
    "method": "GET"
  },
  "updateMe": {
    "path": "/me",
    "method": "PATCH"
  },
  "getSettings": {
    "path": "/me/settings",
    "method": "GET"
  },
  "updateSettings": {
    "path": "/me/settings",
    "method": "PATCH"
  },
  "getUser": {
    "path": "/users/{userId}",
    "method": "GET"
  },
  "listUserWorks": {
    "path": "/users/{userId}/works",
    "method": "GET"
  },
  "listMyWorks": {
    "path": "/me/works",
    "method": "GET"
  },
  "listMyBookmarks": {
    "path": "/me/bookmarks",
    "method": "GET"
  },
  "getFeed": {
    "path": "/feed",
    "method": "GET"
  },
  "createWork": {
    "path": "/works",
    "method": "POST"
  },
  "getWork": {
    "path": "/works/{workId}",
    "method": "GET"
  },
  "updateWork": {
    "path": "/works/{workId}",
    "method": "PUT"
  },
  "deleteWork": {
    "path": "/works/{workId}",
    "method": "DELETE"
  },
  "publishWork": {
    "path": "/works/{workId}/publish",
    "method": "POST"
  },
  "likeWork": {
    "path": "/works/{workId}/like",
    "method": "PUT"
  },
  "unlikeWork": {
    "path": "/works/{workId}/like",
    "method": "DELETE"
  },
  "bookmarkWork": {
    "path": "/works/{workId}/bookmark",
    "method": "PUT"
  },
  "unbookmarkWork": {
    "path": "/works/{workId}/bookmark",
    "method": "DELETE"
  },
  "listComments": {
    "path": "/works/{workId}/comments",
    "method": "GET"
  },
  "createComment": {
    "path": "/works/{workId}/comments",
    "method": "POST"
  },
  "deleteComment": {
    "path": "/comments/{commentId}",
    "method": "DELETE"
  },
  "createUpload": {
    "path": "/uploads",
    "method": "POST"
  },
  "completeUpload": {
    "path": "/uploads/{uploadId}/complete",
    "method": "POST"
  },
  "cancelUpload": {
    "path": "/uploads/{uploadId}",
    "method": "DELETE"
  },
  "getAsset": {
    "path": "/assets/{assetId}",
    "method": "GET"
  },
  "resolveMusicLink": {
    "path": "/music-links/resolve",
    "method": "POST"
  }
} as const;
