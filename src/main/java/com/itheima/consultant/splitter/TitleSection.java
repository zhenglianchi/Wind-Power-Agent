package com.itheima.consultant.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

public record TitleSection(
        String title,
        String parentTitle,
        String content,
        int level,
        String source,
        int sectionIndex,
        int startLine,
        int endLine
) {
    public TextSegment toSegment() {
        Metadata metadata = Metadata.from("title", title)
                .put("parent_title", parentTitle != null ? parentTitle : "")
                .put("level", level)
                .put("source", source != null ? source : "unknown")
                .put("section_index", sectionIndex)
                .put("start_line", startLine)
                .put("end_line", endLine);

        return TextSegment.from(content, metadata);
    }

    public String fullTitle() {
        if (parentTitle != null && !parentTitle.isEmpty()) {
            return parentTitle + " > " + title;
        }
        return title;
    }

    public int contentLength() {
        return content != null ? content.length() : 0;
    }
}
