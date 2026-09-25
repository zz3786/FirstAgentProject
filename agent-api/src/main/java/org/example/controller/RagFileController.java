package org.example.controller;

import org.example.rag.config.RagProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/rag/file")
public class RagFileController {

    private final RagProperties ragProperties;

    public RagFileController(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 按 docId 下载/预览
     * 示例：/fap/rag/file/download/00a4e0e7-817e-4305-a909-34c001e39155
     */
    @GetMapping("/download/{docId}")
    public ResponseEntity<Resource> downloadByDocId(@PathVariable String docId) throws Exception {
        // 安全校验
        if (docId.contains("..") || docId.contains("/") || docId.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }

        File dir = new File(ragProperties.getFileStorageDir());
        // ★ 匹配 {docId}.{ext}
        File[] files = dir.listFiles((d, n) -> n.startsWith(docId + "."));

        if (files == null || files.length == 0) {
            return ResponseEntity.notFound().build();
        }

        File file = files[0];
        String displayName = file.getName();
        MediaType mediaType = resolveMediaType(displayName);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" +
                                URLEncoder.encode(displayName, StandardCharsets.UTF_8) + "\"")
                .contentType(mediaType)
                .body(new FileSystemResource(file));
    }

    private MediaType resolveMediaType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (lower.endsWith(".docx")) {
            return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        if (lower.endsWith(".doc")) {
            return MediaType.parseMediaType("application/msword");
        }
        if (lower.endsWith(".xlsx")) {
            return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }
        if (lower.endsWith(".txt")) {
            return MediaType.TEXT_PLAIN;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}