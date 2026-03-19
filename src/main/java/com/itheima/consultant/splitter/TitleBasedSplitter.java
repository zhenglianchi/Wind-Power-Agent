package com.itheima.consultant.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class TitleBasedSplitter implements DocumentSplitter {

    private final int maxSectionSize;
    private final int overlapSize;
    private final boolean enableSubSplit;

    private static final List<TitlePattern> TITLE_PATTERNS = List.of(
            new TitlePattern(Pattern.compile("^(第[一二三四五六七八九十百零]+[章节篇部])\\s*(.*)$"), 1),
            new TitlePattern(Pattern.compile("^(第[0-9]+[章节篇部])\\s*(.*)$"), 1),
            new TitlePattern(Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)\\s+(.+)$"), 2),
            new TitlePattern(Pattern.compile("^([一二三四五六七八九十]+)[、.．]\\s*(.+)$"), 2),
            new TitlePattern(Pattern.compile("^(\\d+)[、.．]\\s*(.+)$"), 2),
            new TitlePattern(Pattern.compile("^([A-Z]\\d*)[、.．\\s]+(.+)$"), 2),
            new TitlePattern(Pattern.compile("^【(.+)】$"), 2),
            new TitlePattern(Pattern.compile("^(.+)\\s*[:：]\\s*$"), 2)
    );

    public TitleBasedSplitter(int maxSectionSize, int overlapSize, boolean enableSubSplit) {
        this.maxSectionSize = maxSectionSize;
        this.overlapSize = overlapSize;
        this.enableSubSplit = enableSubSplit;
    }

    @Override
    public List<TextSegment> split(Document document) {
        log.info("📄 [标题分割] 开始处理文档");

        String text = document.text();
        Metadata metadata = document.metadata();
        String source = metadata.getString("source");
        if (source == null) {
            source = "unknown";
        }

        List<TitleSection> sections = parseByTitle(text, source);

        List<TextSegment> segments = new ArrayList<>();
        for (TitleSection section : sections) {
            if (enableSubSplit && section.content().length() > maxSectionSize) {
                List<TextSegment> subSegments = subSplit(section);
                segments.addAll(subSegments);
            } else if (!section.content().trim().isEmpty()) {
                segments.add(section.toSegment());
            }
        }

        log.info("✅ [标题分割] 完成，共生成 {} 个片段", segments.size());
        return segments;
    }

    private List<TitleSection> parseByTitle(String text, String source) {
        List<TitleSection> sections = new ArrayList<>();
        String[] lines = text.split("\n");

        StringBuilder currentContent = new StringBuilder();
        String currentTitle = "概述";
        int currentLevel = 0;
        String parentTitle = "";
        int sectionIndex = 0;
        int startLine = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmedLine = line.trim();

            TitleInfo titleInfo = detectTitle(trimmedLine);

            if (titleInfo != null && !isFalsePositive(trimmedLine)) {
                if (currentContent.length() > 0) {
                    sections.add(new TitleSection(
                            currentTitle,
                            parentTitle,
                            currentContent.toString().trim(),
                            currentLevel,
                            source,
                            sectionIndex++,
                            startLine,
                            i - 1
                    ));
                }

                if (titleInfo.level <= currentLevel) {
                    parentTitle = findParentTitle(sections, titleInfo.level);
                }

                currentTitle = titleInfo.title;
                currentLevel = titleInfo.level;
                currentContent = new StringBuilder();
                startLine = i + 1;

                log.debug("📌 [标题识别] Level {}: {}", currentLevel, currentTitle);
            } else {
                if (currentContent.length() > 0 || !trimmedLine.isEmpty()) {
                    currentContent.append(line).append("\n");
                }
            }
        }

        if (currentContent.length() > 0) {
            sections.add(new TitleSection(
                    currentTitle,
                    parentTitle,
                    currentContent.toString().trim(),
                    currentLevel,
                    source,
                    sectionIndex,
                    startLine,
                    lines.length - 1
            ));
        }

        return sections;
    }

    private TitleInfo detectTitle(String line) {
        if (line.isEmpty() || line.length() > 100) {
            return null;
        }

        for (TitlePattern pattern : TITLE_PATTERNS) {
            Matcher matcher = pattern.pattern.matcher(line);
            if (matcher.matches()) {
                String title = matcher.group(matcher.groupCount()).trim();
                if (!title.isEmpty() && title.length() < 50) {
                    return new TitleInfo(title, pattern.level);
                }
            }
        }

        if (line.length() < 30 && !line.endsWith("。") && !line.endsWith("，") && !line.endsWith(".")) {
            if (line.matches("^[\\u4e00-\\u9fa5a-zA-Z0-9\\s]+$")) {
                return new TitleInfo(line, 3);
            }
        }

        return null;
    }

    private boolean isFalsePositive(String line) {
        String[] falsePositivePatterns = {
                "注意", "警告", "提示", "说明", "备注",
                "图\\d+", "表\\d+", "公式\\d+",
                "见第", "参见", "参考"
        };

        for (String pattern : falsePositivePatterns) {
            if (line.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    private String findParentTitle(List<TitleSection> sections, int currentLevel) {
        for (int i = sections.size() - 1; i >= 0; i--) {
            if (sections.get(i).level() < currentLevel) {
                return sections.get(i).title();
            }
        }
        return "";
    }

    private List<TextSegment> subSplit(TitleSection section) {
        List<TextSegment> subSegments = new ArrayList<>();
        String content = section.content();

        int start = 0;
        int subIndex = 0;

        while (start < content.length()) {
            int end = Math.min(start + maxSectionSize, content.length());

            if (end < content.length()) {
                int lastPeriod = content.lastIndexOf("。", end);
                int lastNewline = content.lastIndexOf("\n", end);
                int breakPoint = Math.max(lastPeriod, lastNewline);

                if (breakPoint > start + maxSectionSize / 2) {
                    end = breakPoint + 1;
                }
            }

            String subContent = content.substring(start, end).trim();

            if (!subContent.isEmpty()) {
                Metadata metadata = Metadata.from("title", section.title())
                        .put("parent_title", section.parentTitle())
                        .put("level", section.level())
                        .put("source", section.source())
                        .put("section_index", section.sectionIndex())
                        .put("sub_index", subIndex++);

                subSegments.add(TextSegment.from(subContent, metadata));
            }

            start = end - overlapSize;
            if (start < 0) start = 0;
            if (start >= content.length()) break;
        }

        log.debug("🔪 [子分割] '{}' 分割为 {} 个子片段", section.title(), subSegments.size());
        return subSegments;
    }

    private record TitlePattern(Pattern pattern, int level) {}

    private record TitleInfo(String title, int level) {}
}
