package com.itheima.consultant.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class KnowledgeBaseUpdateEvent extends ApplicationEvent {

    private final String updateType;
    private final String documentName;

    public KnowledgeBaseUpdateEvent(Object source, String updateType) {
        this(source, updateType, null);
    }

    public KnowledgeBaseUpdateEvent(Object source, String updateType, String documentName) {
        super(source);
        this.updateType = updateType;
        this.documentName = documentName;
    }
}
