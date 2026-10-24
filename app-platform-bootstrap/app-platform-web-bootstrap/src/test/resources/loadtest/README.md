# 知识库解析并发防护压测工具

验证 `DocumentParseBudget`(字节预算) + Tika/pdfbox 内存上限对批量文档解析的堆内存防护效果。
背景: 2026-10-12 线上事故(48 个 PDF 1.34GB 批量上传导致解析线程并行加载文件,2.5 分钟 OOM)。

## 文件

- `../java/com/xspaceagi/knowledge/LoadTestHarness.java` — 压测入口(无 Spring 依赖,
  直接驱动生产的 `TikaImageAwareParser` 与 `DocumentParseBudget`;不以 Test 结尾,
  surefire 不会自动执行)
- `make_test_pdfs.py` — 生成测试 PDF(文本书/扫描书两种形态)

## 运行方法(容器)

```bash
# 1. 生成测试 PDF
python3 make_test_pdfs.py /tmp/loadtest/docs

# 2. 编译测试类并运行(在仓库根目录)
docker run --rm -v "$PWD":/src -v <m2缓存卷>:/root/.m2 -v /tmp/mvn-settings.xml:/root/.m2/settings.xml \
  -v /tmp/loadtest/docs:/docs -w /src maven:3.9-eclipse-temurin-17 sh -c '
  mvn -pl app-platform-bootstrap/app-platform-web-bootstrap test-compile -q
  MOD=app-platform-bootstrap/app-platform-web-bootstrap/target/test-classes
  CP=$MOD:$(cat <classpath文件>)
  # A组: 禁用预算(模拟修复前),预期OOM
  java -Xmx512m -cp $CP com.xspaceagi.knowledge.LoadTestHarness 0 20 14 /docs/scan20.pdf /docs/small1.pdf
  # B组: 预算128MB(修复后默认),预期0失败且堆峰值远低于上限
  java -Xmx512m -cp $CP com.xspaceagi.knowledge.LoadTestHarness 134217728 20 14 /docs/scan20.pdf /docs/small1.pdf'
```

classpath 可用 `mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt` 生成。

## 参数

`LoadTestHarness <budgetBytes> <threads> <rounds> <pdf...>`

| 参数 | 说明 |
|---|---|
| budgetBytes | 字节预算,0=禁用(对照组) |
| threads | 并发线程数(模拟上传提交风暴+重试并发) |
| rounds | 每个文件的重复轮数(文档总数=文件数×轮数) |

## 2026-10-19 基线结果(512MB 堆, 20 并发, 28 文档)

| 组 | 结果 |
|---|---|
| 预算禁用(修复前) | 1 秒内 6/21 任务 OOM,堆峰值 509/512MB |
| 预算 128MB(修复后) | 0 失败 0 OOM,堆峰值 358MB(70% 上限),其余线程在信号量排队 |
