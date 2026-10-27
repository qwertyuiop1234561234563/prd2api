package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
     * 病毒扫描钩子：预留对接企业病毒扫描服务，返回 false 表示命中病毒
     */
    public static boolean virusScan(byte[] bytes, String fileName) {
        log.debug("[病毒扫描钩子] 文件={} 大小={}B，当前未接入扫描引擎，默认放行", fileName, bytes.length);
        return true;
    }

    /**
     * 本地落盘：{basePath}/{tenantId}/{yyyyMMdd}/{uuid}.{suffix}
     *
     * @return 绝对路径字符串
     */
    public static String saveLocal(String basePath, String tenantId, byte[] bytes, String suffix) {
        try {
            Path dir = Paths.get(basePath, tenantId, LocalDate.now().format(DATE_DIR));
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + "." + suffix);
            Files.write(target, bytes);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "文件落盘失败: " + e.getMessage(), e);
        }
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
