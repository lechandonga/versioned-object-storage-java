package com.github.lechandonga.versionedobjectstoragejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 存储配置：本地文件系统根目录。
 */
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private Path root = Path.of("./data");

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root;
    }
}
