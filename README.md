# Document Analysis and Information Extraction System

A Java-based system designed for automated information extraction and analysis from legal and financial documents. This project focuses on processing Korean legal documents, particularly those related to personal rehabilitation and bankruptcy cases, using OCR (Optical Character Recognition) and barcode analysis.

## Overview

This system provides a modular architecture to handle complex document processing tasks, including image pre-processing, layout analysis, table extraction, and data validation. It is built to support high-accuracy data extraction from various document formats such as PDF and scanned images.

## Core Domains

The system is specialized in analyzing the following document types:
- Court Institutional Identification
- Creditor Lists and Debt Records
- Account Reports and Financial Statements
- Rehabilitation Plan Submissions
- Payment Schedules and Tables

## Key Features

- Optical Character Recognition (OCR): High-performance text extraction using Tesseract.
- Table Structure Analysis: Advanced logic for identifying and parsing complex table structures within documents.
- Barcode and QR Code Recognition: Automated detection and decoding of embedded barcodes.
- Image Pre-processing: Enhancement and normalization of scanned documents using OpenCV to improve extraction accuracy.
- Document Layout Analysis: Logical section identification through pattern matching and contextual analysis.

## Technical Stack

- Language: Java 17
- Framework: Spring Boot 3.5.13
- Build Tool: Gradle
- Image Processing: OpenCV 4.9.0
- OCR Engine: Tess4J 5.17.0 (Tesseract wrapper)
- PDF Processing: Apache PDFBox 2.0.31
- Barcode Recognition: ZXing 3.5.3
- Documentation: SpringDoc OpenAPI 2.8.5

## Project Structure

The project follows a multi-module architecture:
- ecfs-app: The main application entry point and API layer.
- ecfs-ocr: Core OCR logic, table extraction, and document analysis services.
- ecfs-barcode: Dedicated module for barcode and QR code recognition.

## Requirements

- Java 17 or higher
- Tesseract OCR engine installed on the host system
- OpenCV native libraries configured for the target environment

## Build and Run

To build the project:
```bash
./gradlew clean build
```

To run the application:
```bash
./gradlew :ecfs-app:bootRun
```
