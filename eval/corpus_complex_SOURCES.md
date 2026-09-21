# corpus_complex 语料来源清单

15 份中文真实文档（docx / xlsx / pdf 各 5），全部取自业界公开的文档智能 / RAG 评测语料库，用于 512 最小 chunk 的复杂语料效果评测。
下载日期：2026-09-19。许可与出处见下表。

## docx（5）

| 文件 | 来源数据集 | 说明 |
|---|---|---|
| 日照市就业补助资金管理办法.docx | [superdoc-dev/docx-corpus](https://huggingface.co/datasets/superdoc-dev/docx-corpus)（type=policies, lang=zh；ODC-By） | 地方财政制度办法（征求意见稿），章节条款结构，约 9.9k 字 |
| 舟山建筑施工安全标准化评选公示.docx | superdoc-dev/docx-corpus（type=reports, lang=zh） | 评选结果公示 + 名单附表，约 8.1k 字 |
| 浙江省装配式建筑职业技能竞赛实施方案.docx | superdoc-dev/docx-corpus（type=administrative, lang=zh） | 竞赛实施方案（附件形式），约 5.4k 字 |
| 威海市残联法治宣传教育第八个五年规划通知.docx | superdoc-dev/docx-corpus（type=policies, lang=zh） | 红头文件：规划印发通知，约 3.5k 字 |
| ChatGLM2-6B微调方案简介.docx | [MetaGLM/FinGLM](https://github.com/MetaGLM/FinGLM)（智谱 ChatGLM 金融大模型挑战赛官方仓库，Apache-2.0） | 参赛队伍技术方案文档，含代码块，约 4k 字 |

docx-corpus 的文件本体存于其 CDN（manifest API `api.docxcorp.us/manifest?type=<t>&lang=zh`），
文件名为内容哈希，入库前按内容重命名为上表语义化文件名。

## xlsx（5）

全部来自 MetaGLM/FinGLM 仓库 `code/Chatglm反卷总局/app/data/` 与 `code/finglm_all/prepare_data/`（Apache-2.0）：

| 文件 | 原路径 | 说明 |
|---|---|---|
| FinGLM行业分类表.xlsx | code/finglm_all/prepare_data/industry.xlsx | 上市公司行业分类 |
| FinGLM公司年报摘要.xlsx | code/Chatglm反卷总局/app/data/company_annual_reports.xlsx | 公司年报元数据 |
| FinGLM公司基本信息.xlsx | code/Chatglm反卷总局/app/data/baseinfo.xlsx | 上市公司基本信息 |
| FinGLM公司高管信息.xlsx | code/Chatglm反卷总局/app/data/people.xlsx | 高管简历信息 |
| FinGLM资产负债表静态.xlsx | code/Chatglm反卷总局/app/data/balance_static.xlsx | 资产负债表静态数据 |

## pdf（5）

全部来自 MetaGLM/FinGLM 仓库（Apache-2.0）：

| 文件 | 原路径 | 说明 |
|---|---|---|
| 金宇生物2020年年度报告.pdf | code/结婚买房代代韭菜/serving/test_data/allpdf/ | 上市公司年报，195 页，文本型 |
| 安靠智电2019年年度报告.pdf | tools/pdf_to_html/存放pdf/ | 上市公司年报，312 页，文本型 |
| ChatGLM3-6B技术解读.pdf | slides/chatglm3-6b.pdf | 模型技术解读 slides，30 页 |
| FinGLM方案分享nsddd.pdf | slides/nsddd.pdf | 参赛方案分享 slides，14 页 |
| FinGLM方案分享流宝真人.pdf | slides/流宝真人.pdf | 参赛方案分享 slides，16 页 |
