package cn.local.ocr;

import org.json.*;
import java.io.IOException;

/**
 * 拍照文档裁剪的桩实现（移植适配）。
 *
 * 源版（suishou-ocr-android）依赖 OpenCV（DocumentBoundaryDetector/CropGeometry/Imgproc）做
 * 边界检测与透视矫正，本次移植不引入 OpenCV，因此 begin/detect/preview/confirm 四个写路径方法
 * 保持源签名、统一抛出 IOException，调用方（RecognitionController.beginCrop/previewCrop/confirmCrop）
 * 无需任何改动即可编译；对应入口在宿主 UI 暂不暴露。
 *
 * pending(JSONObject) 沿用源逻辑（不依赖 OpenCV）：documentCrop 存在且 state ≠ confirmed 即视为
 * 裁剪未确认。这样即使旧数据/导出数据带 documentCrop 字段，start()/autoStart() 的拦截行为也与源一致。
 * 所有调用在宿主工作线程执行，与源契约一致。
 */
final class DocumentCrop {
    static boolean pending(JSONObject doc) {
        JSONObject crop=doc.optJSONObject("documentCrop");return crop!=null&&!crop.optString("state").equals("confirmed");
    }
    static JSONObject begin(DocumentStore store,JSONObject doc)throws Exception {
        throw new IOException("拍照裁剪未随本次移植");
    }
    static JSONObject detect(DocumentStore store,JSONObject doc)throws Exception {
        throw new IOException("拍照裁剪未随本次移植");
    }
    static JSONObject preview(DocumentStore store,JSONObject doc,JSONArray coordinates,String policy)throws Exception {
        throw new IOException("拍照裁剪未随本次移植");
    }
    static JSONObject confirm(DocumentStore store,JSONObject doc,String token)throws Exception {
        throw new IOException("拍照裁剪未随本次移植");
    }
}
