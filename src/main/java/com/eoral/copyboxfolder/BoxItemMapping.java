package com.eoral.copyboxfolder;

public class BoxItemMapping {

    private BoxItemType type;
    private String sourceId;
    private String targetId;

    public BoxItemMapping(BoxItemType type, String sourceId, String targetId) {
        this.type = type;
        this.sourceId = sourceId;
        this.targetId = targetId;
    }

    public BoxItemType getType() {
        return type;
    }

    public void setType(BoxItemType type) {
        this.type = type;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }
}
