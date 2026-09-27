package com.github.lechandonga.versionedobjectstoragejava.storage.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个对象（key）的全部版本元数据。
 */
public class ObjectMetadata {

    private String key;
    private List<ObjectVersion> versions = new ArrayList<>();

    public ObjectMetadata() {
    }

    public ObjectMetadata(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public List<ObjectVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<ObjectVersion> versions) {
        this.versions = versions;
    }
}
