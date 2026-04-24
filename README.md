# Document Structure Analysis & OCR Engine

![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.13-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![OpenCV](https://img.shields.io/badge/OpenCV-4.9.0-5C3EE8?style=for-the-badge&logo=opencv&logoColor=white)
![Tesseract](https://img.shields.io/badge/Tesseract-5.x-blue?style=for-the-badge&logo=tesseract&logoColor=white)

A high-performance Optical Character Recognition (OCR) and document parsing engine built with Spring Boot. This service specializes in extracting structured data from Korean legal and financial documents, analyzing spatial coordinates for table reconstruction, and identifying embedded barcodes.

## Key Features

* **Advanced Document & Table Parsing**
    * **Table Structure Analysis**: Identifies physical contours and grid structures using OpenCV and Tabula-based heuristic engines.
    * **Domain-Specific Parsing**: Specialized parsers (DebtList, Account, etc.) using pattern matching and fuzzy logic for structured data extraction.
* **Hybrid OCR & Vision Engine**
    * **Tesseract Integration**: High-accuracy text extraction using Tess4J (Native Tesseract wrapper).
    * **Image Pre-processing**: Scanned document enhancement (noise reduction, deskewing) and layout analysis using OpenCV.
* **Barcode & QR Recognition**
    * Automated detection and decoding of embedded codes (ZXing integration) for document identification.
* **Asynchronous Job & SSE Streaming**
    * **Non-blocking Execution**: Long-running OCR tasks are managed as asynchronous jobs with lifecycle management.
    * **Real-Time Updates**: Server-Sent Events (SSE) support for tracking processing progress and receiving results page-by-page.
* **Architectural Standards**
    * **Hexagonal Architecture**: Clear separation between core logic, ports, and adapters (Web/Infrastructure).
    * **Type-Safe Domain Models**: Immutable data structures using Java records for extraction payloads.

## API Overview

### 1. OCR Job Management
Asynchronous processing for document analysis and quality diagnosis.
* **Endpoints:**
    * `POST /api/ocr/jobs/data`: Register a text and table extraction job.
    * `POST /api/ocr/jobs/quality`: Register an image quality diagnosis job.
    * `POST /api/ocr/jobs/structure`: Register a document contour analysis job.
* **Consumes:** `multipart/form-data` (Supports images and multi-page documents)
* **Real-time Monitoring:** `GET /api/ocr/jobs/{jobId}/stream` (Produces `text/event-stream`)

### 2. Barcode Recognition
Direct decoding of barcodes and QR codes from images.
* **Endpoint:** `POST /api/barcode/decode`
* **Consumes:** `multipart/form-data`
* **Produces:** `application/json`

## Project Structure

The project follows a multi-module Gradle architecture to ensure modularity and scalability:

    tesseract-java/
     ├── ecfs-app/      # Main application entry point and configuration
     ├── ecfs-ocr/      # Core OCR, table extraction, and document analysis
     │    ├── adapter/  # Web controllers (In) and External engines (Out)
     │    ├── application/ # Use cases and service orchestration
     │    ├── domain/   # Business models (Parser, Reconstructor, Vision)
     │    └── common/   # Shared constants, exceptions, and utilities
     └── ecfs-barcode/  # Dedicated module for barcode and QR code recognition

## Getting Started

### Prerequisites
* Java 17 or higher
* Tesseract OCR Engine (installed on host system)
* OpenCV Native Libraries (configured for the target environment)

### Installation & Execution
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/tesseract-java.git
   ```
2. Build the project:
   ```bash
   ./gradlew clean build
   ```
3. Run the application:
   ```bash
   ./gradlew :ecfs-app:bootRun
   ```

## Testing

The project includes unit and integration tests covering OCR accuracy, table reconstruction logic, and API endpoints.

```bash
# Run all tests
./gradlew test
```

## License
This project is licensed under the MIT License.
