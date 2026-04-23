package com.softgram.ecfs.ocr.adapter.out.document;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import com.softgram.ecfs.ocr.common.exception.ProcessingException;

import lombok.extern.slf4j.Slf4j;

/**
 * 대형 PDF 페이지를 감지하여 표준 규격으로 분할하고 재구성하는 컴포넌트.
 */
@Slf4j
@Component
public class PdfProcessor {

    /** PDF 처리용 최대 메모리 (50MB) */
    private static final long PDF_MEMORY_USAGE = 50 * 1024 * 1024L;

    /** 분할 결정을 위한 페이지 높이 임계치 */
    private static final float THRESHOLD_PAGE_HEIGHT = 3000f;

    /** 페이지 분할 기준 높이 (A4 규격) */
    private static final float SPLIT_HEIGHT = 842f;

    /**
     * 페이지 크기를 확인하여 필요한 경우 분할을 실행합니다.
     */
    public File preprocessPdfIfNecessary(File file, String extension) {
        try (PDDocument document = PDDocument.load(file, MemoryUsageSetting.setupMixed(PDF_MEMORY_USAGE))) {
            boolean needsSplit = false;

            for (PDPage page : document.getPages()) {
                if (page.getMediaBox().getHeight() > THRESHOLD_PAGE_HEIGHT) {
                    needsSplit = true;
                    break;
                }
            }

            if (!needsSplit) {
                return file;
            }

            log.info("Large page detected in {}. Splitting...", file.getName());
            return splitPdfPages(file, document, extension);

        } catch (Exception e) {
            log.error("Failed to preprocess PDF", e);
            return file;
        }
    }

    /**
     * 임계치를 초과하는 페이지를 여러 개의 표준 크기 페이지로 분할합니다.
     */
    private File splitPdfPages(File originalFile, PDDocument sourceDoc, String extension) throws IOException {
        String baseName = FilenameUtils.getBaseName(originalFile.getName());
        String splitSuffix = "_split_" + UUID.randomUUID().toString().substring(0, 8) + "." + extension;
        File outputFile = new File(originalFile.getParent(), baseName + splitSuffix);

        try (PDDocument outDoc = new PDDocument()) {
            LayerUtility layerUtility = new LayerUtility(outDoc);

            for (PDPage page : sourceDoc.getPages()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ProcessingException("Operation interrupted");
                }

                PDRectangle mediaBox = page.getMediaBox();
                float height = mediaBox.getHeight();
                float width = mediaBox.getWidth();

                if (height <= THRESHOLD_PAGE_HEIGHT) {
                    outDoc.importPage(page);
                } else {
                    int chunks = (int) Math.ceil(height / SPLIT_HEIGHT);
                    log.info("Splitting large page (height: {}pt) into {} chunks", height, chunks);

                    PDFormXObject form = layerUtility.importPageAsForm(sourceDoc, page);

                    for (int i = 0; i < chunks; i++) {
                        float chunkHeight = Math.min(SPLIT_HEIGHT, height - i * SPLIT_HEIGHT);

                        if (chunkHeight <= 0) {
                            continue;
                        }

                        PDPage newPage = new PDPage(new PDRectangle(width, chunkHeight));
                        outDoc.addPage(newPage);

                        try (PDPageContentStream contentStream = new PDPageContentStream(outDoc, newPage)) {
                            contentStream.saveGraphicsState();

                            float ty = chunkHeight - (height - i * SPLIT_HEIGHT);
                            Matrix matrix = Matrix.getTranslateInstance(0, ty);
                            contentStream.transform(matrix);

                            contentStream.drawForm(form);
                            contentStream.restoreGraphicsState();
                        }
                    }
                }
            }

            outDoc.save(outputFile);
            log.info("Created split PDF: {}. Total pages: {}", outputFile.getAbsolutePath(), outDoc.getNumberOfPages());
        }

        return outputFile;
    }
}
