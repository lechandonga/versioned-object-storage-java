package com.github.lechandonga.versionedobjectstoragejava.tests;

import com.github.lechandonga.versionedobjectstoragejava.storage.BlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 接口层测试：验证失败原因以可区分的形式返回。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObjectControllerTests {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("storage.root", () -> tempDir.toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void uploadReadRollbackAndFailureReasons() throws Exception {
        byte[] v1 = "hello-v1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "hello-v2".getBytes(StandardCharsets.UTF_8);

        // 上传两个版本
        mvc.perform(post("/objects/doc").contentType(MediaType.APPLICATION_OCTET_STREAM).content(v1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(1))
                .andExpect(jsonPath("$.sha256").value(BlobStore.sha256Hex(v1)));
        mvc.perform(post("/objects/doc").contentType(MediaType.APPLICATION_OCTET_STREAM).content(v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(2));

        // 最新版本与历史版本均可读
        mvc.perform(get("/objects/doc")).andExpect(content().bytes(v2));
        mvc.perform(get("/objects/doc/versions/1")).andExpect(content().bytes(v1));

        // 回滚产生新版本 v3，内容为 v1
        mvc.perform(post("/objects/doc/rollback/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(3));
        mvc.perform(get("/objects/doc")).andExpect(content().bytes(v1));

        // 失败原因可区分
        mvc.perform(get("/objects/ghost")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("ObjectNotFound"));
        mvc.perform(get("/objects/doc/versions/99")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("VersionNotFound"));

        // 校验不一致：400，且原有版本不受影响
        mvc.perform(post("/objects/doc").contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("tampered".getBytes(StandardCharsets.UTF_8))
                        .header("X-Content-Sha256", BlobStore.sha256Hex(v1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("ChecksumMismatch"));
        mvc.perform(get("/objects/doc")).andExpect(content().bytes(v1));

        // 清理版本后读取返回 410 Gone，与 404 可区分
        mvc.perform(delete("/objects/doc/versions/2")).andExpect(status().isNoContent());
        mvc.perform(get("/objects/doc/versions/2")).andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("VersionPurged"));
    }
}
