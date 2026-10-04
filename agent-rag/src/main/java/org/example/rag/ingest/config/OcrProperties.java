package org.example.rag.ingest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OCR 配置项
 * <p>
 * <b>设计意图</b>：
 * 把"可调参数"从代码中剥离，方便不同环境（开发/测试/生产）用 yml 覆盖，
 * 避免 OCR 参数（超时、语言、精度）修改一次就要重新打包。
 * <p>
 * <b>为什么不用 @Value 逐个注入</b>：
 * OCR 相关参数有 7 个，逐个 @Value 会让 OcrService 构造函数膨胀。
 * 用 @ConfigurationProperties 集中管理，OcrService 只依赖一个对象。
 * <p>
 * yml 覆盖示例：
 * <pre>
 * app:
 *   ocr:
 *     enabled: true
 *     tessdata-path: ./tessdata/
 *     language: chi_sim+eng
 *     timeout-seconds: 30
 * </pre>
 */
@Data                       // Lombok：自动生成 getter/setter/toString 等
@Component                  // 注册为 Spring Bean
@ConfigurationProperties(prefix = "app.ocr")   // 绑定 app.ocr.* 前缀的配置
public class OcrProperties {

    /**
     * 是否启用 OCR
     * <p>
     * <b>为什么需要这个开关</b>：
     * 1. 单元测试时可以关闭，避免依赖系统级 Tesseract 安装
     * 2. 生产环境若 OCR 服务异常，可以快速降级而不重启
     */
    private boolean enabled = true;

    /**
     * tessdata 目录
     * <p>
     * 存放语言包文件（chi_sim.traineddata / eng.traineddata）。
     * <b>不要指向系统 Tesseract 的默认目录</b>——用项目自带目录，
     * 避免"运维改了系统语言包导致你项目识别率变化"的隐性依赖。
     * <p>
     * 建议路径：项目根目录 ./tessdata/，随代码一起版本管理。
     */
    private String tessdataPath = "./tessdata/";

    /**
     * 识别语言
     * <p>
     * 格式：用 + 连接多个语言包，如 chi_sim+eng。
     * <b>注意</b>：语言越多，识别越慢（线性增加）。
     * 中文场景建议 chi_sim+eng 组合——中文文档常夹杂英文术语。
     */
    private String language = "chi_sim+eng";

    /**
     * 单张图片识别超时（秒）
     * <p>
     * <b>为什么必须设置超时</b>：
     * Tesseract 遇到超大图（如 5000×7000）或损坏图时可能进入"死循环"，
     * 没有超时控制会阻塞整个入库流程，导致一批文件全部卡死。
     */
    private int timeoutSeconds = 30;

    /**
     * 有效文本最小长度
     * <p>
     * OCR 结果少于这个字数，视为"未识别到有效内容"。
     * <b>为什么需要这个阈值</b>：
     * 空白图/纯色图/图标类图片，OCR 会返回一些噪声字符（如 "m", "."），
     * 这些噪声入向量库后成为"垃圾数据"，被检索时污染结果。
     * 5 是一个经验值——短于 5 个字符基本无信息量。
     */
    private int minTextLength = 5;

    /**
     * 页面分割模式（Page Segmentation Mode）
     * <p>
     * Tesseract 通过 PSM 参数决定"如何理解页面布局"：
     * - 3  = 全自动（默认，适合绝大多数场景）
     * - 6  = 假设为统一文本块（适合纯文字截图）
     * - 7  = 假设为单行文本（适合验证码）
     * - 11 = 稀疏文本（适合零散分布的文本）
     * <p>
     * 若发现某些文档识别率低，可以尝试调整此参数。
     */
    private int pageSegMode = 3;
}