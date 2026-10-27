package com.dbappsecurity.prd2api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.annotation.Audit;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.dto.FileQueryDTO;
import com.dbappsecurity.prd2api.service.FileService;
import com.dbappsecurity.prd2api.vo.FileMetaVO;
import com.dbappsecurity.prd2api.vo.FileUploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件接口：上传（含 md5 去重、相似度查重）、分页查询、删除
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/file")
@Tag(name = "文件接入", description = "md / txt / docx / pdf 上传与清理")
public class FileController {

    private final FileService fileService;

    @Audit("文件上传")
    @Operation(summary = "上传文件",
            description = "后缀 + 魔数双校验，md5 去重命中直接复用；返回 fileMetaId 用于创建任务。"
                    + "若检测到相似文档会返回 similarFileMetaId 与 similarity")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RespModel<FileUploadVO> upload(
            @Parameter(description = "待上传文件，最大 20MB") @RequestPart("file") MultipartFile file) {
        return RespModel.success(fileService.upload(file));
    }

    @Operation(summary = "分页查询文件", description = "普通用户仅能看本租户，管理员可传 tenantId 查看指定租户")
    @GetMapping("/page")
    public RespModel<Page<FileMetaVO>> page(@ParameterObject @Valid FileQueryDTO dto) {
        return RespModel.success(fileService.pageFiles(dto));
    }

    @Audit("文件删除")
    @Operation(summary = "删除文件", description = "删除本地文件并逻辑删除元信息")
    @DeleteMapping("/{fileMetaId}")
    public RespModel<Void> delete(@Parameter(description = "文件元数据ID") @PathVariable String fileMetaId) {
        fileService.deleteFile(fileMetaId);
        return RespModel.success();
    }
}
