package com.itheima.consultant.splitter;

import com.itheima.consultant.service.AliyunDocParserService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class AliyunSmartSplitter implements DocumentSplitter {

    private final AliyunDocParserService docParserService;
    private final int maxSectionSize;
    private final int overlapSize;
    private final boolean enableSubSplit;

    public AliyunSmartSplitter(AliyunDocParserService docParserService,
                               int maxSectionSize, int overlapSize, boolean enableSubSplit) {
        this.docParserService = docParserService;
        this.maxSectionSize = maxSectionSize;
        this.overlapSize = overlapSize;
        this.enableSubSplit = enableSubSplit;
    }

    @Override
    public List<TextSegment> split(Document document) {
        log.info("🤖 [阿里云智能分割] 开始处理文档");

        Metadata docMetadata = document.metadata();
        String source = docMetadata.getString("source");
        if (source == null) {
            source = "unknown";
        }

        String absolutePath = docMetadata.getString("absolute_path");

        if (docParserService.isEnabled() && absolutePath != null) {
            Path filePath = Path.of(absolutePath);
            AliyunDocParserService.DocParseResult parseResult = docParserService.parseDocument(filePath);

            if (parseResult != null && !parseResult.paragraphs.isEmpty()) {
                return splitByAliyunResult(parseResult, source);
            }
        }

        log.warn("⚠️ [阿里云智能分割] 阿里云解析不可用或解析失败，使用本地标题分割");
        return splitByLocalTitle(document.text(), source);
    }

    private List<TextSegment> splitByAliyunResult(AliyunDocParserService.DocParseResult parseResult, String source) {
        List<TextSegment> segments = new ArrayList<>();
        StringBuilder currentSection = new StringBuilder();
        String currentTitle = "概述";
        int currentLevel = 1;
        int sectionIndex = 0;

        for (AliyunDocParserService.Paragraph para : parseResult.paragraphs) {
            if (para.isTitle) {
                if (currentSection.length() > 0) {
                    List<TextSegment> sectionSegments = createSectionSegments(
                            currentTitle, currentSection.toString(), source, currentLevel, sectionIndex++);
                    segments.addAll(sectionSegments);
                }

                currentTitle = para.text.trim();
                currentLevel = para.level;
                currentSection = new StringBuilder();
                log.debug("📌 [阿里云智能分割] 标题 Level {}: {}", currentLevel, currentTitle);
            } else {
                if (para.isTable && para.llmResult != null && !para.llmResult.isEmpty()) {
                    currentSection.append(para.llmResult).append("\n\n");
                } else if (para.text != null && !para.text.trim().isEmpty()) {
                    currentSection.append(para.text).append("\n");
                }
            }
        }

        if (currentSection.length() > 0) {
            List<TextSegment> sectionSegments = createSectionSegments(
                    currentTitle, currentSection.toString(), source, currentLevel, sectionIndex);
            segments.addAll(sectionSegments);
        }

        log.info("✅ [阿里云智能分割] 完成，共生成 {} 个片段", segments.size());
        return segments;
    }

    private List<TextSegment> createSectionSegments(String title, String content, String source, int level, int sectionIndex) {
        List<TextSegment> segments = new ArrayList<>();
        String trimmedContent = content.trim();

        if (trimmedContent.isEmpty()) {
            return segments;
        }

        if (!enableSubSplit || trimmedContent.length() <= maxSectionSize) {
            Metadata metadata = Metadata.from("title", title)
                    .put("source", source)
                    .put("level", level)
                    .put("section_index", sectionIndex);

            segments.add(TextSegment.from(trimmedContent, metadata));
        } else {
            segments.addAll(subSplit(title, trimmedContent, source, level, sectionIndex));
        }

        return segments;
    }

    private List<TextSegment> subSplit(String title, String content, String source, int level, int sectionIndex) {
        List<TextSegment> subSegments = new ArrayList<>();
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
                Metadata metadata = Metadata.from("title", title)
                        .put("source", source)
                        .put("level", level)
                        .put("section_index", sectionIndex)
                        .put("sub_index", subIndex++);

                subSegments.add(TextSegment.from(subContent, metadata));
            }

            start = end - overlapSize;
            if (start < 0) start = 0;
            if (start >= content.length()) break;
        }

        log.debug("🔪 [子分割] '{}' 分割为 {} 个子片段", title, subSegments.size());
        return subSegments;
    }

    private List<TextSegment> splitByLocalTitle(String text, String source) {
        List<TextSegment> segments = new ArrayList<>();
        String[] lines = text.split("\n");

        StringBuilder currentContent = new StringBuilder();
        String currentTitle = "概述";
        int currentLevel = 1;
        int sectionIndex = 0;

        for (String line : lines) {
            String trimmedLine = line.trim();
            Integer titleLevel = detectTitleLevel(trimmedLine);

            if (titleLevel != null) {
                if (currentContent.length() > 0) {
                    List<TextSegment> sectionSegments = createSectionSegments(
                            currentTitle, currentContent.toString(), source, currentLevel, sectionIndex++);
                    segments.addAll(sectionSegments);
                }

                currentTitle = extractTitleText(trimmedLine);
                currentLevel = titleLevel;
                currentContent = new StringBuilder();
            } else {
                currentContent.append(line).append("\n");
            }
        }

        if (currentContent.length() > 0) {
            List<TextSegment> sectionSegments = createSectionSegments(
                    currentTitle, currentContent.toString(), source, currentLevel, sectionIndex);
            segments.addAll(sectionSegments);
        }

        log.info("✅ [本地标题分割] 完成，共生成 {} 个片段", segments.size());
        return segments;
    }

    private Integer detectTitleLevel(String line) {
        if (line.isEmpty() || line.length() > 100) {
            return null;
        }

        if (line.matches("^第[一二三四五六七八九十百零]+[章节篇部].*")) return 1;
        if (line.matches("^第\\d+[章节篇部].*")) return 1;
        if (line.matches("^\\d+\\.\\d+\\.\\d+\\s+.*")) return 3;
        if (line.matches("^\\d+\\.\\d+\\s+.*")) return 2;
        if (line.matches("^[一二三四五六七八九十]+[、.．].*")) return 2;
        if (line.matches("^\\d+[、.．].*")) return 2;

        return null;
    }

    private String extractTitleText(String line) {
        line = line.replaceAll("^第[一二三四五六七八九十百零\\d]+[章节篇部]\\s*", "");
        line = line.replaceAll("^\\d+(\\.\\d+)*\\.?\\s*", "");
        line = line.replaceAll("^[一二三四五六七八九十]+[、.．]\\s*", "");
        return line.trim();
    }
}
