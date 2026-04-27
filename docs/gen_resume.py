#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成杨卫简历PDF"""

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, HRFlowable
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
import os, glob

# ── 注册中文字体 ──────────────────────────────────────────────
FONT_PATHS = [
    "/System/Library/Fonts/PingFang.ttc",
    "/Library/Fonts/Arial Unicode MS.ttf",
]
FONT_DIRS = [
    "/System/Library/Fonts/",
    "/Library/Fonts/",
    os.path.expanduser("~/Library/Fonts/"),
    "/opt/homebrew/share/fonts/",
]

def find_font(name_hints):
    for d in FONT_DIRS:
        for hint in name_hints:
            matches = glob.glob(os.path.join(d, f"*{hint}*"))
            if matches:
                return matches[0]
    return None

# 尝试注册PingFang
try:
    pdfmetrics.registerFont(TTFont("PF", "/System/Library/Fonts/PingFang.ttc", subfontIndex=0))
    pdfmetrics.registerFont(TTFont("PF-Bold", "/System/Library/Fonts/PingFang.ttc", subfontIndex=1))
    FONT = "PF"
    FONT_BOLD = "PF-Bold"
except:
    # fallback: STHeiti
    try:
        pdfmetrics.registerFont(TTFont("STH", "/System/Library/Fonts/STHeiti Light.ttc", subfontIndex=0))
        pdfmetrics.registerFont(TTFont("STH-Bold", "/System/Library/Fonts/STHeiti Medium.ttc", subfontIndex=0))
        FONT = "STH"
        FONT_BOLD = "STH-Bold"
    except:
        FONT = "Helvetica"
        FONT_BOLD = "Helvetica-Bold"

print(f"使用字体: {FONT}")

# ── 颜色 ──────────────────────────────────────────────────────
DARK = colors.HexColor("#1a1a2e")
BLUE = colors.HexColor("#2c3e50")
RED  = colors.HexColor("#c0392b")
GRAY = colors.HexColor("#555555")
LIGHT= colors.HexColor("#f5f7fa")
WHITE= colors.white

W, H = A4   # 595 x 842

# ── 样式 ──────────────────────────────────────────────────────
def s(name, **kw):
    base = kw.pop("base", "Normal")
    st = getSampleStyleSheet()[base].clone(name)
    st.fontName = kw.pop("fontName", FONT)
    for k, v in kw.items():
        setattr(st, k, v)
    return st

S_NAME   = s("name",    fontSize=22, leading=28, fontName=FONT_BOLD, textColor=DARK, spaceAfter=2)
S_TARGET = s("target",  fontSize=10, leading=14, textColor=GRAY)
S_CONTACT= s("contact", fontSize=9,  leading=13, textColor=GRAY)
S_BODY   = s("body",    fontSize=9.5,leading=14, textColor=colors.black)
S_BOLD   = s("bold",    fontSize=9.5,leading=14, fontName=FONT_BOLD)
S_SMALL  = s("small",   fontSize=9,  leading=13, textColor=GRAY)
S_ITEM   = s("item",    fontSize=9.5,leading=14, leftIndent=12, bulletIndent=2)

def sec_title(text):
    """深色背景章节标题行"""
    t = Table([[Paragraph(text, s("sh", fontSize=10.5, leading=14,
                fontName=FONT_BOLD, textColor=WHITE))]],
              colWidths=[W - 72*mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,-1), BLUE),
        ("TOPPADDING",   (0,0), (-1,-1), 3),
        ("BOTTOMPADDING",(0,0), (-1,-1), 3),
        ("LEFTPADDING",  (0,0), (-1,-1), 8),
    ]))
    return t

def row2(left, right, lw=None, bold_left=False):
    """左右两列行"""
    lf = FONT_BOLD if bold_left else FONT
    lw = lw or (W - 72*mm - 110)
    t = Table([[Paragraph(left,  s("rl", fontSize=10, leading=14, fontName=lf)),
                Paragraph(right, s("rr", fontSize=9,  leading=14, textColor=GRAY))]],
              colWidths=[lw, None])
    t.setStyle(TableStyle([
        ("VALIGN", (0,0), (-1,-1), "BOTTOM"),
        ("TOPPADDING",   (0,0), (-1,-1), 0),
        ("BOTTOMPADDING",(0,0), (-1,-1), 1),
        ("LEFTPADDING",  (0,0), (-1,-1), 0),
        ("RIGHTPADDING", (0,0), (-1,-1), 0),
    ]))
    return t

def bullet(text, color=BLUE):
    return Paragraph(f'<font color="#{color.hexval()[2:]}">•</font> {text}', S_ITEM)

def subhead(text):
    return Paragraph(f'<font name="{FONT_BOLD}" color="#{BLUE.hexval()[2:]}">{"▸"} {text}</font>', S_BODY)

def metric(text):
    """关键数字标红"""
    return f'<font name="{FONT_BOLD}" color="#{RED.hexval()[2:]}">{text}</font>'

def sp(h=4):
    return Spacer(1, h)

# ── 构建内容 ──────────────────────────────────────────────────
story = []

# 姓名 + 联系方式
story.append(Paragraph("杨  卫", S_NAME))
story.append(Paragraph("求职意向：<font name='{}' color='#{}'>Java 后端开发工程师（大数据平台 / AI 应用方向）</font>".format(
    FONT_BOLD, RED.hexval()[2:]), S_TARGET))
story.append(sp(6))

# 联系信息 两列
ct = Table([
    [Paragraph("姓名：杨卫",        S_CONTACT), Paragraph("年龄：27",               S_CONTACT)],
    [Paragraph("电话：17695965214", S_CONTACT), Paragraph("邮箱：ywei_20@126.com",  S_CONTACT)],
    [Paragraph("微信：yangw_0122",  S_CONTACT), Paragraph("",                        S_CONTACT)],
], colWidths=[(W-72*mm)/2, (W-72*mm)/2])
ct.setStyle(TableStyle([
    ("TOPPADDING",   (0,0), (-1,-1), 1),
    ("BOTTOMPADDING",(0,0), (-1,-1), 1),
    ("LEFTPADDING",  (0,0), (-1,-1), 0),
]))
story.append(ct)
story.append(sp(8))

# ── 教育经历 ──
story.append(sec_title("教育经历"))
story.append(sp(4))
edu = Table([
    [Paragraph("<b>中国民航大学</b>", S_BODY),
     Paragraph("硕士 · 计算机技术 · 天津", S_SMALL),
     Paragraph("2020.09 — 2023.06", S_SMALL)],
    [Paragraph("<b>中国民航大学</b>", S_BODY),
     Paragraph("本科 · 计算机科学与技术 · 天津", S_SMALL),
     Paragraph("2016.09 — 2020.06", S_SMALL)],
], colWidths=[100, None, 90])
edu.setStyle(TableStyle([
    ("TOPPADDING",   (0,0),(-1,-1), 2),
    ("BOTTOMPADDING",(0,0),(-1,-1), 2),
    ("LEFTPADDING",  (0,0),(-1,-1), 0),
    ("VALIGN",       (0,0),(-1,-1), "MIDDLE"),
]))
story.append(edu)
story.append(sp(8))

# ── 技术技能 ──
story.append(sec_title("技术技能"))
story.append(sp(4))
skills = [
    ["语言框架", "Java（熟练）、Spring Boot、MyBatis、Spring MVC"],
    ["数据库",   "MySQL（索引优化/MVCC/事务隔离）、Redis（分布式锁/缓存设计/限流）、OceanBase、MatrixDB、PostgreSQL"],
    ["大数据",   "Hive、ClickHouse、Flink CDC、DataX"],
    ["中间件",   "Kafka、Neo4j（知识图谱）、Elasticsearch"],
    ["AI 应用",  "Agentic RAG、NL2SQL、MCP 协议、大模型工程落地（LLM Application）"],
    ["并发/JVM", "线程池调优、JUC、JVM 内存模型/GC 调优（G1）"],
    ["工具平台", "Docker、Git、XXL-Job、Apollo 动态配置"],
]
sk_data = [[Paragraph(f'<font name="{FONT_BOLD}" color="#{BLUE.hexval()[2:]}">{r[0]}</font>', S_BODY),
            Paragraph(r[1], S_BODY)] for r in skills]
sk_table = Table(sk_data, colWidths=[60, None])
sk_style = [
    ("TOPPADDING",   (0,0),(-1,-1), 2.5),
    ("BOTTOMPADDING",(0,0),(-1,-1), 2.5),
    ("LEFTPADDING",  (0,0),(-1,-1), 6),
    ("VALIGN",       (0,0),(-1,-1), "TOP"),
]
for i in range(0, len(skills), 2):
    sk_style.append(("BACKGROUND", (0,i),(-1,i), LIGHT))
sk_table.setStyle(TableStyle(sk_style))
story.append(sk_table)
story.append(sp(8))

# ── 工作经历 ──
story.append(sec_title("工作经历"))
story.append(sp(4))
story.append(row2(
    "<b>理想汽车 | 大数据开发工程师 | 企业智能-大数据平台</b>",
    "2023.06 — 至今  |  北京", bold_left=True))
story.append(sp(3))
for t in [
    f"主导<b>数据服务平台、指标知识库</b>从 0 到 1 建设，负责系统整体架构设计与核心功能开发；",
    f"负责数智平台后端开发，覆盖数据服务、数据消费、指标管理、知识图谱等多个业务域的架构迭代；",
    f"主导 <b>Agentic RAG + MCP</b> 技术落地，实现自然语言转 SQL 能力上线，降低数据消费门槛；",
    f"负责数据服务平台、指标中心、达芬奇 BI、配置中心、告警中心等多个核心系统持续迭代，跨前后端协同推进项目落地。",
]:
    story.append(bullet(t))
story.append(sp(8))

# ── 项目经历 ──
story.append(sec_title("项目经历"))
story.append(sp(5))

# 项目1
story.append(row2(
    "<b>数据服务平台（共享平台 + 开放平台）— 管理端 owner</b>",
    "2025.03 — 至今", bold_left=True))
story.append(sp(2))
story.append(Paragraph(
    "面向理想汽车全业务的数据服务化能力平台，双平台协同构成，为智能驾驶、用户运营、商业分析等多场景提供 API 资产全生命周期管理（开发→授权→访问→监控→统计）。",
    S_SMALL))
story.append(sp(4))

story.append(subhead("① 主导开放平台从 0 到 1 建设（6 期迭代）"))
for t in [
    "构建<b>文档中心门户</b>：提供 API/消息资产搜索、调试、版本对比、智能导入（MCP 工具）、权限申请等能力，支撑用户自助服务；",
    "建设<b>应用管理模块</b>：区分发布应用（数据生产方）和订阅应用（数据消费方），支持应用授权、集群管理、接口管理、监控统计；",
    "搭建<b>运营分析大盘</b>：多维度数据看板，实现 API 资产接入→授权→访问→监控→统计的全生命周期闭环管理。",
]:
    story.append(bullet(t))

story.append(subhead("② 共享平台重构与智能化建设"))
for t in [
    f"支持 MySQL、OB、MatrixDB、PG 等 {metric('6 种')}主流数据库统一接入，覆盖结构化与非结构化数据共享场景；",
    f"完成 <b>Agentic RAG + MCP</b> 自然语言转 SQL 能力上线，业务人员可通过自然语言直接查询数据；",
    '通过\u201c一键发布\u201d与开放平台联动，实现 API 从开发到运维的无缝衔接。',
]:
    story.append(bullet(t))

story.append(subhead("③ 核心数据指标"))
for t in [
    f"平台接入应用数 {metric('100+')}，覆盖研发、产品、运营等多业务场景；",
    f"API 日均调用量 {metric('百万级')}；API 接入至发布完成 {metric('5s 内')}。",
]:
    story.append(bullet(t))

story.append(sp(8))

# 项目2
story.append(row2(
    "<b>指标知识库 — 项目 owner</b>",
    "2024.07 — 2025.03", bold_left=True))
story.append(sp(2))
story.append(Paragraph(
    "面向数据场景的指标知识图谱服务（基于 Neo4j），弥补指标平台与实际业务间的信息 Gap，提升 NL2SQL 准确率，支持产品、数仓、算法等多角色协同，实现从指标规划到应用的全流程闭环。",
    S_SMALL))
story.append(sp(3))
for t in [
    f"<b>管理端 5 大核心模块</b>：提供指标、维度、Schema 等知识资产线上统一管理与运维，支持数据同步、智能交互，实现知识资产数字化转型；",
    f"<b>OpenAPI 服务模块</b>：提供 {metric('20+')} 核心接口，支持指标定位、智能推荐、知识检索、数据合成，为算法训练/推理提供标准化服务；",
    f"<b>Job 服务模块</b>：实现知识库与指标平台数据自动同步与一致性保障，支持数据告警等后台任务；",
    f"知识库规模：指标 {metric('757 个')}、维度 {metric('931 个')}；OpenAPI 接口覆盖算法侧与数据合成侧全场景。",
]:
    story.append(bullet(t))

story.append(sp(8))

# ── 自我评价 ──
story.append(sec_title("自我评价"))
story.append(sp(4))
for t in [
    "具备完整的后端工程能力，熟悉从需求分析、技术方案设计到架构落地的全流程，有<b>多个 0 到 1 项目及系统重构</b>的实战经验；",
    f"深度投入 AI 大模型工程化落地，主导 <b>Agentic RAG + NL2SQL + MCP</b> 在企业级数据平台的实际落地，具备大模型应用工程化思维；",
    "持续深耕 Java 并发（线程池/JUC）、JVM 调优、MySQL/Redis 底层原理，追求技术深度与业务价值的结合；",
    "认真负责，具备良好的跨团队协作与抗压能力，热爱篮球。",
]:
    story.append(bullet(t))

# ── 输出 PDF ──────────────────────────────────────────────────
OUT = "/Users/yangwei/IdeaProjects/interview/docs/杨卫_简历_优化版.pdf"
doc = SimpleDocTemplate(
    OUT,
    pagesize=A4,
    leftMargin=18*mm, rightMargin=18*mm,
    topMargin=16*mm,  bottomMargin=16*mm,
)
doc.build(story)
print(f"✅ 已生成: {OUT}")
