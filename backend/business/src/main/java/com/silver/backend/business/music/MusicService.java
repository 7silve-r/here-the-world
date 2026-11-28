package com.silver.backend.business.music;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.MusicLink;
import com.silver.backend.business.dto.MusicLinkInput;
import com.silver.backend.business.persistence.MusicRow;
import com.silver.backend.business.persistence.mapper.MusicMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.TransactionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MusicService {
    private static final Logger LOG = LoggerFactory.getLogger(MusicService.class);
    private static final Pattern DIRECT_LINK = Pattern.compile(
            "(?i)[a-z][a-z0-9+.-]*://[^\\s\\p{Z}\\p{Cc}<>\"'“”‘’]+");
    private static final Pattern LINK = Pattern.compile(
            "(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*://[^\\s\\p{Z}\\p{Cc}<>\"'“”‘’，。；！？、]+");
    private static final String OPENING = "([{（【《「『";
    private static final String CLOSING = ")]}）】》」』";
    private static final int MAX_URL_LENGTH = 4096;

    @Autowired
    private MusicMapper mapper;
    @Autowired
    private TransactionHelper tx;
    @Autowired
    private Views views;

    public MusicLink resolve(Viewer viewer, MusicLinkInput input) {
        String owner = viewer.requiredUser();
        long started = System.nanoTime();
        URI source = normalize(extract(input.input()));
        String provider = provider(source.getHost());
        MusicLink result = tx.write(
                () -> {
                    MusicRow row = new MusicRow();
                    row.setId(Values.id());
                    row.setOwnerId(owner);
                    row.setProvider(provider);
                    row.setSourceUrl(source.toASCIIString());
                    row.setMetadataStatus("PARTIAL");
                    row.setCreatedAt(Values.now());
                    mapper.insert(row);
                    return views.music(row);
                });
        LOG.debug("Music link resolved musicLinkId={}, provider={}, elapsedMs={}",
                result.id(), result.provider(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private String extract(String text) {
        String trimmed = text.strip();
        // 完整 URL 优先保留，避免截断域名、路径或查询参数中本来合法的 Unicode 标点。
        if (DIRECT_LINK.matcher(trimmed).matches()) return trimmed;
        Matcher matcher = LINK.matcher(text);
        if (!matcher.find()) {
            throw new ApiException(400, "MUSIC_LINK_INVALID", "分享内容需要包含一条 HTTPS 地址");
        }
        String candidate = matcher.group();
        int start = matcher.start();
        if (matcher.find()) {
            throw new ApiException(400, "MUSIC_LINK_INVALID", "分享内容只能包含一条链接");
        }
        // 分享文案尾部的句读、以及紧邻 URL 的成对包裹括号不属于链接。
        while (!candidate.isEmpty() && ".,;!".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        int opening = start > 0 ? OPENING.indexOf(text.charAt(start - 1)) : -1;
        if (opening >= 0 && candidate.endsWith(String.valueOf(CLOSING.charAt(opening)))) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private URI normalize(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()) {
                throw new IllegalArgumentException("HTTPS required");
            }
            String tail = Objects.toString(uri.getRawPath(), "")
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            URI server = new URI("https://" + authority(uri) + tail).parseServerAuthority();
            if (server.getHost() == null || server.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid host or port");
            }
            String host = server.getHost().toLowerCase(Locale.ROOT);
            int port = server.getPort();
            String address = host + (port < 0 || port == 443 ? "" : ":" + port);
            String source = new URI("https://" + address + tail).normalize().toASCIIString();
            if (source.length() > MAX_URL_LENGTH) {
                throw new ApiException(400, "MUSIC_LINK_INVALID", "规范化后的地址过长");
            }
            return URI.create(source);
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            throw new ApiException(400, "MUSIC_LINK_INVALID", "音乐链接无效");
        }
    }

    private String authority(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null || authority.contains("@")) {
            throw new IllegalArgumentException("Host required without user information");
        }
        if (authority.startsWith("[")) return authority.toLowerCase(Locale.ROOT);
        int colon = authority.lastIndexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        String port = colon < 0 ? "" : authority.substring(colon);
        host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > 253) {
            throw new IllegalArgumentException("Invalid host length");
        }
        return host + port;
    }

    private String provider(String host) {
        if (matches(host, "music.163.com") || matches(host, "163cn.tv")) return "NETEASE";
        if (matches(host, "y.qq.com") || matches(host, "c.y.qq.com")) return "QQ";
        if (matches(host, "kugou.com")) return "KUGOU";
        if (matches(host, "spotify.com") || matches(host, "spotify.link")) return "SPOTIFY";
        if (matches(host, "music.apple.com")) return "APPLE_MUSIC";
        return "OTHER";
    }

    private boolean matches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
