import {useEffect, useMemo, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import {AlertCircle, Copy, FileText, Hash, Loader2, Search, X} from 'lucide-react';
import {KnowledgeBaseChunk, knowledgeBaseApi} from '../api/knowledgebase';

interface Props {
  isOpen: boolean;
  knowledgeBaseId: number | null;
  knowledgeBaseName: string;
  onClose: () => void;
}

/**
 * 知识库向量切片预览抽屉。
 * 点击知识库列表「查看分块」时打开，展示该知识库被切成的所有 chunks。
 */
export default function ChunkViewerDrawer({isOpen, knowledgeBaseId, knowledgeBaseName, onClose}: Props) {
  const [chunks, setChunks] = useState<KnowledgeBaseChunk[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  // 打开抽屉或切换 KB 时重新拉数据
  useEffect(() => {
    if (!isOpen || knowledgeBaseId == null) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    setKeyword('');
    knowledgeBaseApi
      .getChunks(knowledgeBaseId)
      .then((data) => {
        if (cancelled) return;
        setChunks(data);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setError(e.message || '加载分块失败');
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen, knowledgeBaseId]);

  // ESC 关闭
  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isOpen, onClose]);

  const filteredChunks = useMemo(() => {
    if (!keyword.trim()) return chunks;
    const kw = keyword.trim().toLowerCase();
    return chunks.filter((c) => c.content.toLowerCase().includes(kw));
  }, [chunks, keyword]);

  const totalChars = useMemo(
    () => chunks.reduce((sum, c) => sum + c.contentLength, 0),
    [chunks],
  );

  const avgChars = chunks.length === 0 ? 0 : Math.round(totalChars / chunks.length);

  const handleCopy = async (chunk: KnowledgeBaseChunk) => {
    try {
      await navigator.clipboard.writeText(chunk.content);
      setCopiedId(chunk.id);
      setTimeout(() => setCopiedId(null), 1500);
    } catch {
      // 忽略剪贴板失败（HTTPS / 用户授权限制）
    }
  };

  return (
    <AnimatePresence>
      {isOpen && (
        <>
          <motion.div
            key="backdrop"
            initial={{opacity: 0}}
            animate={{opacity: 1}}
            exit={{opacity: 0}}
            transition={{duration: 0.15}}
            className="fixed inset-0 bg-slate-900/30 z-[60]"
            onClick={onClose}
          />
          <motion.aside
            key="drawer"
            initial={{x: '100%'}}
            animate={{x: 0}}
            exit={{x: '100%'}}
            transition={{type: 'tween', duration: 0.25, ease: 'easeOut'}}
            className="fixed top-0 right-0 h-screen w-full sm:w-[640px] bg-[#FAF9F5] border-l border-slate-200 z-[70] flex flex-col"
          >
            {/* Header */}
            <header className="px-6 pt-6 pb-4 border-b border-slate-200">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <div className="text-[11px] uppercase tracking-[0.08em] text-slate-500 font-medium mb-1">
                    Embedding Chunks
                  </div>
                  <h2 className="font-display text-xl font-semibold text-slate-900 truncate" title={knowledgeBaseName}>
                    {knowledgeBaseName}
                  </h2>
                </div>
                <button
                  onClick={onClose}
                  className="p-2 -mr-2 text-slate-500 hover:text-slate-900 hover:bg-slate-100 rounded-md transition-colors"
                  aria-label="关闭"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              {/* 统计行 */}
              <div className="mt-4 flex items-center gap-4 text-[13px] text-slate-600">
                <div className="flex items-center gap-1.5">
                  <Hash className="w-3.5 h-3.5 text-primary-500" />
                  <span className="font-medium text-slate-900">{chunks.length}</span>
                  <span>个分块</span>
                </div>
                <span className="text-slate-300">·</span>
                <div className="flex items-center gap-1.5">
                  <FileText className="w-3.5 h-3.5 text-slate-400" />
                  <span className="font-medium text-slate-900">{totalChars.toLocaleString()}</span>
                  <span>总字符</span>
                </div>
                {chunks.length > 0 && (
                  <>
                    <span className="text-slate-300">·</span>
                    <span>
                      平均 <span className="font-medium text-slate-900">{avgChars}</span> 字/块
                    </span>
                  </>
                )}
              </div>

              {/* 搜索框 */}
              {chunks.length > 0 && (
                <div className="mt-4 relative">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                  <input
                    type="text"
                    value={keyword}
                    onChange={(e) => setKeyword(e.target.value)}
                    placeholder="按文本搜索分块…"
                    className="w-full pl-9 pr-3 py-2 text-sm bg-white border border-slate-200 rounded-md text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-primary-500/15 focus:border-primary-500 transition-colors"
                  />
                  {keyword && (
                    <span className="absolute right-3 top-1/2 -translate-y-1/2 text-[11px] text-slate-500">
                      {filteredChunks.length}/{chunks.length}
                    </span>
                  )}
                </div>
              )}
            </header>

            {/* Body */}
            <div className="flex-1 overflow-y-auto scrollbar-thin px-6 py-4">
              {loading && (
                <div className="flex flex-col items-center justify-center py-20 text-slate-500">
                  <Loader2 className="w-6 h-6 animate-spin mb-2" />
                  <span className="text-sm">加载分块中…</span>
                </div>
              )}

              {!loading && error && (
                <div className="flex items-start gap-3 p-4 bg-red-50 border border-red-200 rounded-md text-red-700">
                  <AlertCircle className="w-5 h-5 shrink-0 mt-0.5" />
                  <div className="text-sm">
                    <p className="font-medium">加载失败</p>
                    <p className="mt-1 text-red-600">{error}</p>
                  </div>
                </div>
              )}

              {!loading && !error && chunks.length === 0 && (
                <div className="flex flex-col items-center justify-center py-20 text-slate-500">
                  <FileText className="w-8 h-8 text-slate-300 mb-3" />
                  <p className="text-sm">该知识库还没有任何向量切片</p>
                  <p className="text-xs text-slate-400 mt-1">可能向量化失败或正在处理中</p>
                </div>
              )}

              {!loading && !error && chunks.length > 0 && filteredChunks.length === 0 && (
                <div className="flex flex-col items-center justify-center py-20 text-slate-500">
                  <Search className="w-8 h-8 text-slate-300 mb-3" />
                  <p className="text-sm">没有匹配「{keyword}」的分块</p>
                </div>
              )}

              <div className="space-y-3">
                {filteredChunks.map((chunk) => (
                  <article
                    key={chunk.id}
                    className="group bg-white border border-slate-200 rounded-lg overflow-hidden hover:border-slate-300 transition-colors"
                  >
                    <header className="flex items-center justify-between px-4 py-2 bg-slate-50 border-b border-slate-100">
                      <div className="flex items-center gap-3 text-[12px]">
                        <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-primary-50 text-primary-700 rounded-md font-medium">
                          # {chunk.chunkIndex + 1}
                          {chunk.totalChunks > 0 && (
                            <span className="text-primary-500/70 font-normal">/{chunk.totalChunks}</span>
                          )}
                        </span>
                        <span className="text-slate-500">{chunk.contentLength} 字</span>
                      </div>
                      <button
                        onClick={() => handleCopy(chunk)}
                        className="opacity-0 group-hover:opacity-100 flex items-center gap-1 px-2 py-1 text-[11px] text-slate-500 hover:text-slate-900 hover:bg-white rounded transition-all"
                        title="复制此分块"
                      >
                        <Copy className="w-3 h-3" />
                        {copiedId === chunk.id ? '已复制' : '复制'}
                      </button>
                    </header>
                    <pre className="px-4 py-3 text-[13px] leading-relaxed text-slate-800 whitespace-pre-wrap break-words font-sans">
                      {chunk.content || <span className="text-slate-400 italic">（空内容）</span>}
                    </pre>
                  </article>
                ))}
              </div>
            </div>
          </motion.aside>
        </>
      )}
    </AnimatePresence>
  );
}
