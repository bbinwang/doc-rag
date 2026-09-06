"""doc-rag 向量服务：bge-small-zh-v1.5 本地编码 + ChromaDB 持久化向量库（纯本地，数据不出机）。

职责边界（见 CLAUDE.md）：
- 仅负责向量编码与相似度检索，不做切块（切块在 Java 侧完成）
- chunk 以 {docId}:{chunkIndex} 为唯一 id，metadata 携带文档信息用于命中回显
- 双解析模式（plain/deep）各自一个 collection：docrag_plain / docrag_deep
"""
import os
import threading
from typing import Literal

import chromadb
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
# 优先使用 ModelScope 下载到项目内的本地模型目录，避免运行时联网拉取
_LOCAL_MODEL = os.path.join(BASE_DIR, "models", "bge-small-zh-v1.5")
MODEL_NAME = os.environ.get("DOCRAG_EMBED_MODEL") or (
    _LOCAL_MODEL if os.path.isdir(_LOCAL_MODEL) else "BAAI/bge-small-zh-v1.5"
)
CHROMA_DIR = os.environ.get("DOCRAG_CHROMA_DIR", os.path.join(BASE_DIR, "..", "data", "chroma"))
MODES = ("plain", "deep")
# BGE 中文系列检索时的查询侧指令前缀（入库侧不加）
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："

app = FastAPI(title="doc-rag vector service")
model = SentenceTransformer(MODEL_NAME)
client = chromadb.PersistentClient(path=CHROMA_DIR)


def _collection_name(mode: str) -> str:
    return f"docrag_{mode}"


collections = {
    m: client.get_or_create_collection(_collection_name(m), metadata={"hnsw:space": "cosine"})
    for m in MODES
}
# clear 后会删除并重建 collection 句柄；sync def 端点跑在线程池，需防并发 clear/query 用到失效句柄
_lock = threading.Lock()


def _mode_or_400(mode: str) -> str:
    """校验 mode；DELETE 端点额外接受 all（双 collection）。"""
    if mode != "all" and mode not in MODES:
        raise HTTPException(status_code=400, detail=f"mode 仅支持 {'/'.join(MODES)}/all")
    return mode


class DocIn(BaseModel):
    docId: str
    filename: str
    type: str
    mode: Literal["plain", "deep"]
    chunks: list[str]


class QueryIn(BaseModel):
    text: str
    topK: int = 50
    mode: Literal["plain", "deep"]


@app.get("/health")
def health():
    with _lock:
        return {
            "status": "ok",
            "model": MODEL_NAME,
            "vectors": {m: collections[m].count() for m in MODES},
        }


@app.post("/documents")
def add_documents(doc: DocIn):
    if not doc.chunks:
        return {"chunkCount": 0}
    embeddings = model.encode(
        doc.chunks, normalize_embeddings=True, show_progress_bar=False
    ).tolist()
    ids = [f"{doc.docId}:{i}" for i in range(len(doc.chunks))]
    metadatas = [
        {"docId": doc.docId, "filename": doc.filename, "type": doc.type, "chunkIndex": i}
        for i in range(len(doc.chunks))
    ]
    with _lock:
        collection = collections[doc.mode]
        # 先按 docId 清掉旧 chunk 再写入：chunk id 是 {docId}:{index}，
        # 只 upsert 的话块数变少时会残留尾部旧 chunk（幽灵命中）
        collection.delete(where={"docId": doc.docId})
        collection.upsert(ids=ids, embeddings=embeddings, documents=doc.chunks, metadatas=metadatas)
    return {"chunkCount": len(doc.chunks)}


@app.post("/query")
def query(q: QueryIn):
    with _lock:
        collection = collections[q.mode]
        n = collection.count()
        if not q.text.strip() or n == 0:
            return {"hits": []}
        emb = model.encode(QUERY_PREFIX + q.text.strip(), normalize_embeddings=True).tolist()
        res = collection.query(
            query_embeddings=[emb],
            n_results=min(q.topK, n),
            include=["documents", "metadatas", "distances"],
        )
    hits = [
        {
            "docId": meta["docId"],
            "filename": meta["filename"],
            "type": meta["type"],
            "chunk": text,
            # cosine space 下 distance = 1 - similarity
            "similarity": 1.0 - dist,
        }
        for text, meta, dist in zip(
            res["documents"][0], res["metadatas"][0], res["distances"][0]
        )
    ]
    return {"hits": hits}


@app.delete("/documents/{doc_id}")
def delete_doc(doc_id: str, mode: str = "all"):
    """级联删除：mode=all 时两个 collection 都删（后端删除接口/回滚用）。

    where 过滤对不存在的 docId 是无害 no-op，天然幂等。
    """
    _mode_or_400(mode)
    targets = MODES if mode == "all" else (mode,)
    result = {}
    with _lock:
        for m in targets:
            collection = collections[m]
            before = collection.count()
            collection.delete(where={"docId": doc_id})
            result[m] = {"before": before, "after": collection.count()}
    return {"deleted": doc_id, **result}


@app.delete("/documents")
def clear_all(mode: str = "all"):
    """全清向量库（后端「一键清理」用）：删除集合并按原参数重建，返回前后计数。

    不用 collection.delete() 全删：不同 chromadb 版本对空过滤的行为不一致，
    删集合重建是版本安全的全清方式。需在锁内重绑定 collections 句柄。
    """
    _mode_or_400(mode)
    targets = MODES if mode == "all" else (mode,)
    result = {}
    with _lock:
        for m in targets:
            collection = collections[m]
            before = collection.count()
            if before:
                client.delete_collection(_collection_name(m))
                collections[m] = client.get_or_create_collection(
                    _collection_name(m), metadata={"hnsw:space": "cosine"}
                )
            result[m] = {"before": before, "after": collections[m].count()}
    return result


if __name__ == "__main__":
    import uvicorn

    # 端口 8081 归本服务专用（见项目约定），仅供本机/局域网内网调用
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("DOCRAG_PORT", "8081")))
