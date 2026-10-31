package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * 文件工具：后缀白名单、魔数校验、md5、本地存储、病毒扫描钩子
 */
@Slf4j
public final class FileUtil {

    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    public static final Set<String> ALLOWED_SUFFIX = Set.of("md", "txt", "docx", "pdf");

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");

    private FileUtil() {
    }

    /**
     * 取小写后缀，不带点
     */
    public static String suffix(String fileName) {
        if (StrUtil.isBlank(fileName) || !fileName.contains(".")) {
            throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED, "文件名缺少后缀: " + fileName);
        }
        return StrUtil.subSufByLength(fileName, fileName.length() - fileName.lastIndexOf('.') - 1).toLowerCase();
    }

    public static String checkSuffix(String fileName) {
        String suffix = suffix(fileName);
        if (!ALLOWED_SUFFIX.contains(suffix)) {
            throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED,
                    "仅支持 md/txt/docx/pdf，当前文件后缀: " + suffix);
        }
        return suffix;
    }

    /**
     * 魔数校验：防止改后缀绕过
     */
    public static void magicCheck(byte[] bytes, String suffix) {
        if (bytes == null || bytes.length < 4) {
            throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED, "文件内容为空或过短");
        }
        switch (suffix) {
            case "pdf" -> {
                if (!(bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F')) {
                    throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED, "文件内容与 pdf 后缀不匹配");
                }
            }
            case "docx" -> {
                if (!(bytes[0] == 'P' && bytes[1] == 'K')) {
                    throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED, "文件内容与 docx 后缀不匹配");
                }
            }
            default -> {
                // md/txt 为纯文本，出现 NUL 字节视为二进制伪装
                for (byte b : bytes) {
                    if (b == 0) {
                        throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED, "文本文件中存在二进制内容");
                    }
                }
            }
        }
    }

    public static String md5(byte[] bytes) {
        return DigestUtil.md5Hex(bytes);
    }

    /**
     * 文件名清洗：去掉路径部分与控制字符，限制长度并保留后缀。
     * <p>
     * 原始文件名仅用于展示与导出，不参与落盘路径，但仍需清洗避免日志注入与超长入库。
     */
    public static String sanitizeFileName(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return "unknown";
        }
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\u0000-\\u001F\\u007F]", "").trim();
        name = name.replace("..", "_");
        if (StrUtil.isBlank(name) || ".".equals(name)) {
            return "unknown";
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        if (stem.length() > 100) {
            stem = stem.substring(0, 100);
        }
        if (suffix.length() > 20) {
            suffix = suffix.substring(0, 20);
        }
        return stem + suffix;
    }

    /**
     * 病毒扫描钩子：预留对接企业病毒扫描服务，返回 false 表示命中病毒
     */
    public static boolean virusScan(String fileName, long size) {
        log.debug("[病毒扫描钩子] 文件={} 大小={}B，当前未接入扫描引擎，默认放行", fileName, size);
        return true;
    }

    /**
     * 本地落盘：{basePath}/{tenantId}/{yyyyMMdd}/{uuid}.{suffix}
     *
     * @return 绝对路径字符串
     */
    public static String saveLocal(String basePath, String tenantId, byte[] bytes, String suffix) {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            return saveLocalStreaming(basePath, tenantId, input, suffix).path();
        } catch (IOException e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "文件落盘失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式落盘：边写边算 md5 与大小，避免大文件全量读入内存。
     * <p>
     * 同时完成魔数校验与文本二进制伪装检测，落盘后可直接按路径解析，无需再次读入 byte[]。
     */
    public static StoredFile saveLocalStreaming(String basePath, String tenantId,
                                                InputStream input, String suffix) {
        Path target = null;
        try {
            Path base = Paths.get(basePath).toAbsolutePath().normalize();
            String safeTenant = StrUtil.isBlank(tenantId)
                    ? "unknown"
                    : tenantId.replaceAll("[^A-Za-z0-9_-]", "_");
            Path dir = base.resolve(safeTenant).resolve(LocalDate.now().format(DATE_DIR)).normalize();
            // 双保险：即使上游校验遗漏，也禁止落盘到基础目录之外
            if (!dir.startsWith(base)) {
                throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "非法的存储目录");
            }
            Files.createDirectories(dir);
            target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + "." + suffix);

            boolean textFile = "md".equals(suffix) || "txt".equals(suffix);
            byte[] head = new byte[4];
            int headLen = 0;
            long size = 0L;
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (OutputStream out = Files.newOutputStream(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (headLen < head.length) {
                        int copy = Math.min(head.length - headLen, read);
                        System.arraycopy(buffer, 0, head, headLen, copy);
                        headLen += copy;
                    }
                    if (textFile) {
                        for (int i = 0; i < read; i++) {
                            if (buffer[i] == 0) {
                                throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED,
                                        "文本文件中存在二进制内容");
                            }
                        }
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    size += read;
                }
            }
            if (size < head.length) {
                throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED, "文件内容为空或过短");
            }
            magicCheck(head, suffix);
            return new StoredFile(target.toAbsolutePath().toString(),
                    HexFormat.of().formatHex(digest.digest()), size);
        } catch (BizException e) {
            deleteLocalQuietly(target);
            throw e;
        } catch (Exception e) {
            deleteLocalQuietly(target);
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "文件落盘失败: " + e.getMessage(), e);
        }
    }

    private static void deleteLocalQuietly(Path path) {
        if (path == null) {
            return;
        }
        deleteLocalQuietly(path.toAbsolutePath().toString());
    }

    /**
     * 流式落盘结果：路径、md5、真实字节数
     */
    public record StoredFile(String path, String md5, long size) {
    }

    public static byte[] readLocal(String path) {
        try {
            return Files.readAllBytes(Paths.get(path));
        } catch (IOException e) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "文件读取失败: " + path, e);
        }
    }

    public static void deleteLocalQuietly(String path) {
        if (StrUtil.isBlank(path)) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            log.warn("文件删除失败 path={}", path, e);
        }
    }
}
