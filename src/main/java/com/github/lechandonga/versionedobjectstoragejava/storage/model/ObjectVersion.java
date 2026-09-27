package com.github.lechandonga.versionedobjectstoragejava.storage.model;

import java.time.Instant;

/**
 * 单个对象版本的元数据记录。
 */
public class ObjectVersion {

    public enum State {
        ACTIVE,
        PURGED
    }

    private long versionId;
    private String sha256;
    private long size;
    private Instant createdAt;
    private State state;

    public ObjectVersion() {
    }

    public ObjectVersion(long versionId, String sha256, long size, Instant createdAt, State state) {
        this.versionId = versionId;
        this.sha256 = sha256;
        this.size = size;
        this.createdAt = createdAt;
        this.state = state;
    }

    public long getVersionId() {
        return versionId;
    }

    public void setVersionId(long versionId) {
        this.versionId = versionId;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }
}
