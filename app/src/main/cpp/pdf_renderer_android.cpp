// 首轮仅接入单图；App 的 PDF 解析仍由 Java PdfRenderer/PDFBox 负责。
#include "pdf_renderer.hpp"
namespace dococr {
PdfRenderer::PdfRenderer(const uint8_t*,size_t) { throw PdfError("android_pdf_ocr_unsupported","Android 原生 OCR 首轮仅支持单图"); }
PdfRenderer::~PdfRenderer() = default;
PdfGeometry PdfRenderer::geometry(uint32_t,uint32_t) const { throw PdfError("android_pdf_ocr_unsupported","PDF OCR 未接入"); }
std::vector<uint8_t> PdfRenderer::render(uint32_t,uint32_t) const { throw PdfError("android_pdf_ocr_unsupported","PDF OCR 未接入"); }
}
