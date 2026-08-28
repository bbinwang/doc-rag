"""doc-rag 向量服务：bge-small-zh-v1.5 本地编码 + ChromaDB 持久化向量库（纯本地，数据不出机）。

职责边界（见 CLAUDE.md）：
- 仅负责向量编码与相似度检索，不做切块（切块在 Java 侧完成）
- chunk 以 {docId}:{chunkIndex} 为唯一 id，metadata 携带文档信息用于命中回显
"""
import os

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
COLLECTION = "docrag_chunks"
# BGE 中文系列检索时的查询侧指令前缀（入库侧不加）
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："

app = FastAPI(title="doc-rag vector service")
model = SentenceTransformer(MODEL_NAME)
client = chromadb.PersistentClient(path=CHROMA_DIR)
collection = client.get_or_create_collection(COLLECTION, metadata={"hnsw:space": "cosine"})


class DocIn(BaseModel):
    docId: str
    filename: str
    type: str
    chunks: list[str]


class QueryIn(BaseModel):
    text: str
    topK: int = 50


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "vectors": collection.count()}


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
    # upsert：同 docId 重复入库时覆盖旧 chunk，保证幂等
    collection.upsert(ids=ids, embeddings=embeddings, documents=doc.chunks, metadatas=metadatas)
    return {"chunkCount": len(doc.chunks)}


@app.post("/query")
def query(q: QueryIn):
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
def delete_doc(doc_id: str):
    before = collection.count()
    collection.delete(where={"docId": doc_id})
    return {"deleted": doc_id, "before": before, "after": collection.count()}
